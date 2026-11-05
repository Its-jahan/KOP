// MapLibre Native map in Compose. Layers are declared once in the style JSON; at runtime
// only GeoJSON sources are replaced. OSM raster tiles (tile.openstreetmap.org), 256 px,
// with visible attribution; no bulk prefetch.
package com.della.coveragenavigator

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import della.core.LonLat
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.hypot

private const val EMPTY_FC = "{\"type\":\"FeatureCollection\",\"features\":[]}"

/** Style: OSM raster + our overlay layers. Directed coverage is offset to the right of travel. */
val MAP_STYLE = """
{
  "version": 8,
  "sources": {
    "osm": {"type": "raster", "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"], "tileSize": 256, "maxzoom": 19,
            "attribution": "© OpenStreetMap contributors"},
    "zone": {"type": "geojson", "data": $EMPTY_FC},
    "plan": {"type": "geojson", "data": $EMPTY_FC},
    "coverage": {"type": "geojson", "data": $EMPTY_FC},
    "visited": {"type": "geojson", "data": $EMPTY_FC},
    "markers": {"type": "geojson", "data": $EMPTY_FC},
    "location": {"type": "geojson", "data": $EMPTY_FC}
  },
  "layers": [
    {"id": "osm", "type": "raster", "source": "osm"},
    {"id": "zone-fill", "type": "fill", "source": "zone", "filter": ["==", ["geometry-type"], "Polygon"],
     "paint": {"fill-color": "#1565C0", "fill-opacity": 0.07}},
    {"id": "zone-line", "type": "line", "source": "zone", "filter": ["!=", ["geometry-type"], "Point"],
     "paint": {"line-color": "#1565C0", "line-width": 3, "line-dasharray": [2, 1]}},
    {"id": "plan-line", "type": "line", "source": "plan", "layout": {"line-cap": "round", "line-join": "round"},
     "paint": {"line-color": ["match", ["get", "kind"], "outside", "#EF6C00", "#3949AB"], "line-width": 12, "line-opacity": 0.28}},
    {"id": "coverage-line", "type": "line", "source": "coverage", "layout": {"line-join": "round"},
     "paint": {"line-color": ["match", ["get", "status"], "covered", "#2E7D32", "partial", "#F9A825", "blocked", "#4E342E",
                              "unreachable", "#757575", "needsReview", "#8E24AA", "#D32F2F"],
               "line-width": ["interpolate", ["linear"], ["zoom"], 13, 1.5, 17, 4.5],
               "line-offset": ["interpolate", ["linear"], ["zoom"], 13, 1.2, 17, 4.0]}},
    {"id": "visited-line", "type": "line", "source": "visited", "layout": {"line-join": "round"},
     "paint": {"line-color": "#1B5E20", "line-width": ["interpolate", ["linear"], ["zoom"], 13, 1.5, 17, 4.5],
               "line-offset": ["interpolate", ["linear"], ["zoom"], 13, 1.2, 17, 4.0]}},
    {"id": "coverage-arrows", "type": "symbol", "source": "coverage", "minzoom": 15.5,
     "layout": {"symbol-placement": "line", "symbol-spacing": 60, "icon-image": "arrow", "icon-size": 0.5,
                "icon-offset": [0, 8], "icon-allow-overlap": true, "icon-ignore-placement": true,
                "icon-rotation-alignment": "map", "icon-keep-upright": false}},
    {"id": "zone-vertex", "type": "circle", "source": "zone", "filter": ["==", ["geometry-type"], "Point"],
     "paint": {"circle-radius": 11, "circle-color": "#FFFFFF", "circle-stroke-color": "#1565C0", "circle-stroke-width": 4}},
    {"id": "marker-requested", "type": "circle", "source": "markers", "filter": ["==", ["get", "kind"], "requested"],
     "paint": {"circle-radius": 6, "circle-color": "#9E9E9E", "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 2}},
    {"id": "marker-snapped", "type": "circle", "source": "markers", "filter": ["==", ["get", "kind"], "snapped"],
     "paint": {"circle-radius": 10, "circle-color": "#00897B", "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 3}},
    {"id": "marker-snapped-dir", "type": "symbol", "source": "markers", "filter": ["==", ["get", "kind"], "snapped"],
     "layout": {"icon-image": "arrow", "icon-size": 1.1, "icon-rotate": ["-", ["get", "bearing"], 90],
                "icon-rotation-alignment": "map", "icon-allow-overlap": true, "icon-ignore-placement": true, "icon-offset": [26, 0]}},
    {"id": "location-acc", "type": "circle", "source": "location",
     "paint": {"circle-radius": 16, "circle-color": ["case", ["==", ["get", "reliable"], true], "#1E88E5", "#FB8C00"], "circle-opacity": 0.25}},
    {"id": "location", "type": "circle", "source": "location",
     "paint": {"circle-radius": 8, "circle-color": ["case", ["==", ["get", "reliable"], true], "#1E88E5", "#FB8C00"], "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 3}}
  ]
}
""".trimIndent()

private fun arrowBitmap(): Bitmap {
    val size = 48
    @Suppress("UseKtx")
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val path = Path().apply {
        moveTo(size * 0.12f, size * 0.18f)
        lineTo(size * 0.92f, size * 0.5f)
        lineTo(size * 0.12f, size * 0.82f)
        lineTo(size * 0.32f, size * 0.5f)
        close()
    }
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL })
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(33, 33, 33); style = Paint.Style.STROKE; strokeWidth = 4f })
    return bmp
}

private class MapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    val applied = HashMap<String, String>()
    var lastCamera = -1L
    var dragIndex = -1
    var latest: MapLayers? = null

    fun applyLayers() {
        val style = this.style ?: return
        val layers = latest ?: return
        if (!style.isFullyLoaded) return
        fun apply(id: String, json: String) {
            if (applied[id] === json) return
            style.getSourceAs<GeoJsonSource>(id)?.setGeoJson(json)
            applied[id] = json
        }
        apply("zone", layers.zone)
        apply("plan", layers.plan)
        apply("coverage", layers.coverage)
        apply("visited", layers.visited)
        apply("markers", layers.markers)
        apply("location", layers.location)
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapPane(
    layers: MapLayers,
    camera: CameraCmd?,
    vertices: List<LonLat>,
    dragEnabled: Boolean,
    onTap: (LonLat) -> Unit,
    onVertexMoved: (Int, LonLat, Boolean) -> Unit,
    onUserPan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val holder = remember { MapHolder() }
    val tap = rememberUpdatedState(onTap)
    val moved = rememberUpdatedState(onVertexMoved)
    val pan = rememberUpdatedState(onUserPan)
    val verts = rememberUpdatedState(vertices)
    val canDrag = rememberUpdatedState(dragEnabled)
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                holder.map = map
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = true
                map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder().target(LatLng(20.0, 45.0)).zoom(2.0).build()
                map.addOnMapClickListener { ll ->
                    tap.value(LonLat(ll.longitude, ll.latitude))
                    true
                }
                map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                    override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) = pan.value()
                    override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                    override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                })
                map.setStyle(Style.Builder().fromJson(MAP_STYLE)) { style ->
                    style.addImage("arrow", arrowBitmap())
                    holder.style = style
                    holder.applied.clear()
                    holder.applyLayers()
                }
            }
            // Drag-to-edit polygon vertices: grab a vertex within 36 px, move it, release.
            setOnTouchListener { v, ev ->
                val map = holder.map ?: return@setOnTouchListener false
                if (!canDrag.value) return@setOnTouchListener false
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val pts = verts.value
                        var best = -1
                        var bestD = 36f * v.resources.displayMetrics.density
                        pts.forEachIndexed { i, p ->
                            val s = map.projection.toScreenLocation(LatLng(p.lat, p.lon))
                            val d = hypot((s.x - ev.x).toDouble(), (s.y - ev.y).toDouble()).toFloat()
                            if (d < bestD) {
                                bestD = d
                                best = i
                            }
                        }
                        holder.dragIndex = best
                        if (best >= 0) {
                            map.uiSettings.isScrollGesturesEnabled = false
                            true
                        } else false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (holder.dragIndex < 0) return@setOnTouchListener false
                        val ll = map.projection.fromScreenLocation(PointF(ev.x, ev.y))
                        moved.value(holder.dragIndex, LonLat(ll.longitude, ll.latitude), false)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (holder.dragIndex < 0) return@setOnTouchListener false
                        val ll = map.projection.fromScreenLocation(PointF(ev.x, ev.y))
                        moved.value(holder.dragIndex, LonLat(ll.longitude, ll.latitude), true)
                        holder.dragIndex = -1
                        map.uiSettings.isScrollGesturesEnabled = true
                        v.performClick()
                        true
                    }
                    else -> holder.dragIndex >= 0
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier, update = {
        holder.latest = layers
        holder.applyLayers()
        val map = holder.map
        if (map != null && camera != null && camera.id != holder.lastCamera) {
            holder.lastCamera = camera.id
            if (camera.zoom != null) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng((camera.south + camera.north) / 2, (camera.west + camera.east) / 2), camera.zoom), 600)
            } else {
                val b = LatLngBounds.from(camera.north, camera.east, camera.south, camera.west)
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 60), 800)
            }
        }
    })
}
