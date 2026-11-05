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

/** Zoom step request (id makes repeated presses distinct). */
data class ZoomCmd(val id: Long, val delta: Double)

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
    "location": {"type": "geojson", "data": $EMPTY_FC},
    "ahead": {"type": "geojson", "data": $EMPTY_FC}
  },
  "layers": [
    {"id": "osm", "type": "raster", "source": "osm"},
    {"id": "zone-fill", "type": "fill", "source": "zone", "filter": ["==", ["geometry-type"], "Polygon"],
     "paint": {"fill-color": "#1A73E8", "fill-opacity": 0.06}},
    {"id": "zone-line", "type": "line", "source": "zone", "filter": ["!=", ["geometry-type"], "Point"],
     "paint": {"line-color": "#1A73E8", "line-width": 2.5, "line-dasharray": [3, 2]}},
    {"id": "plan-line", "type": "line", "source": "plan", "layout": {"line-cap": "round", "line-join": "round"},
     "paint": {"line-color": ["match", ["get", "kind"], "outside", "#C2185B", "approach", "#C2185B", "#1A73E8"], "line-width": ["interpolate", ["linear"], ["zoom"], 13, 4, 18, 12], "line-opacity": 0.35}},
    {"id": "coverage-line", "type": "line", "source": "coverage", "layout": {"line-join": "round"},
     "paint": {"line-color": ["match", ["get", "status"], "covered", "#12A150", "partial", "#F4B400", "blocked", "#6B7280",
                              "unreachable", "#8E5CC4", "needsReview", "#A0785A", "#FF5A1F"],
               "line-width": ["interpolate", ["linear"], ["zoom"], 13, 1.5, 17, 4.5],
               "line-offset": ["interpolate", ["linear"], ["zoom"], 13, 1.2, 17, 4.0]}},
    {"id": "visited-line", "type": "line", "source": "visited", "layout": {"line-join": "round"},
     "paint": {"line-color": "#12A150", "line-width": ["interpolate", ["linear"], ["zoom"], 13, 1.5, 17, 4.5],
               "line-offset": ["interpolate", ["linear"], ["zoom"], 13, 1.2, 17, 4.0]}},
    {"id": "coverage-arrows", "type": "symbol", "source": "coverage", "minzoom": 15.5,
     "layout": {"symbol-placement": "line", "symbol-spacing": 60, "icon-image": "arrow", "icon-size": 0.5,
                "icon-offset": [0, 8], "icon-allow-overlap": true, "icon-ignore-placement": true,
                "icon-rotation-alignment": "map", "icon-keep-upright": false}},
    {"id": "zone-vertex", "type": "circle", "source": "zone", "filter": ["==", ["geometry-type"], "Point"],
     "paint": {"circle-radius": 11, "circle-color": "#FFFFFF", "circle-stroke-color": "#1A73E8", "circle-stroke-width": 3}},
    {"id": "marker-requested", "type": "circle", "source": "markers", "filter": ["==", ["get", "kind"], "requested"],
     "paint": {"circle-radius": 6, "circle-color": "#9E9E9E", "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 2}},
    {"id": "marker-snapped", "type": "circle", "source": "markers", "filter": ["==", ["get", "kind"], "snapped"],
     "paint": {"circle-radius": 10, "circle-color": "#12A150", "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 3}},
    {"id": "marker-snapped-dir", "type": "symbol", "source": "markers", "filter": ["==", ["get", "kind"], "snapped"],
     "layout": {"icon-image": "arrow", "icon-size": 1.1, "icon-rotate": ["-", ["get", "bearing"], 90],
                "icon-rotation-alignment": "map", "icon-allow-overlap": true, "icon-ignore-placement": true, "icon-offset": [26, 0]}},
    {"id": "ahead-casing", "type": "line", "source": "ahead", "layout": {"line-cap": "round", "line-join": "round"},
     "paint": {"line-color": "#0B3D91", "line-width": ["interpolate", ["linear"], ["zoom"], 13, 8, 18, 22]}},
    {"id": "ahead-line", "type": "line", "source": "ahead", "layout": {"line-cap": "round", "line-join": "round"},
     "paint": {"line-color": "#1A73E8", "line-width": ["interpolate", ["linear"], ["zoom"], 13, 5, 18, 16]}},
    {"id": "ahead-arrows", "type": "symbol", "source": "ahead",
     "layout": {"symbol-placement": "line", "symbol-spacing": 80, "icon-image": "arrow", "icon-size": 0.7,
                "icon-allow-overlap": true, "icon-ignore-placement": true, "icon-rotation-alignment": "map", "icon-keep-upright": false}},
    {"id": "location-acc", "type": "circle", "source": "location",
     "paint": {"circle-radius": 16, "circle-color": ["case", ["==", ["get", "reliable"], true], "#1A73E8", "#F59F00"], "circle-opacity": 0.12}},
    {"id": "location", "type": "circle", "source": "location", "filter": ["<", ["get", "bearing"], 0],
     "paint": {"circle-radius": 10, "circle-color": ["case", ["==", ["get", "reliable"], true], "#1A73E8", "#F59F00"], "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 3}},
    {"id": "location-heading", "type": "symbol", "source": "location", "filter": [">=", ["get", "bearing"], 0],
     "layout": {"icon-image": "vehicle", "icon-size": 1.0, "icon-rotate": ["get", "bearing"], "icon-rotation-alignment": "map",
                "icon-allow-overlap": true, "icon-ignore-placement": true}}
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

private fun vehicleBitmap(): Bitmap {
    val size = 72
    @Suppress("UseKtx")
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val path = Path().apply {
        moveTo(size * 0.5f, size * 0.06f)
        lineTo(size * 0.88f, size * 0.9f)
        lineTo(size * 0.5f, size * 0.7f)
        lineTo(size * 0.12f, size * 0.9f)
        close()
    }
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 115, 232); style = Paint.Style.FILL })
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f })
    return bmp
}

private class MapHolder {
    var driveStyled: Boolean? = null
    var map: MapLibreMap? = null
    var style: Style? = null
    val applied = HashMap<String, String>()
    var lastCamera = -1L
    var lastZoom = 0L
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
        apply("ahead", layers.ahead)
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
    /** While driving: de-emphasise the coverage network so the guided route stands out. */
    driving: Boolean = false,
    /** One-shot zoom step from the floating +/- buttons. */
    zoom: ZoomCmd? = null,
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
                    style.addImage("vehicle", vehicleBitmap())
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
        val st = holder.style
        if (st != null && st.isFullyLoaded && holder.driveStyled != driving) {
            holder.driveStyled = driving
            val op = if (driving) 0.45f else 1.0f
            st.getLayer("coverage-line")?.setProperties(org.maplibre.android.style.layers.PropertyFactory.lineOpacity(op))
            st.getLayer("visited-line")?.setProperties(org.maplibre.android.style.layers.PropertyFactory.lineOpacity(op))
            st.getLayer("coverage-arrows")?.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility(if (driving) "none" else "visible"))
            st.getLayer("plan-line")?.setProperties(org.maplibre.android.style.layers.PropertyFactory.lineOpacity(if (driving) 0.15f else 0.35f))
        }
        val map = holder.map
        if (map != null && zoom != null && zoom.id != holder.lastZoom) {
            holder.lastZoom = zoom.id
            map.animateCamera(CameraUpdateFactory.zoomBy(zoom.delta), 250)
        }
        if (map != null && camera != null && camera.id != holder.lastCamera) {
            holder.lastCamera = camera.id
            if (camera.follow) {
                val h = mapView.height.toDouble()
                val pos = org.maplibre.android.camera.CameraPosition.Builder()
                    .target(LatLng((camera.south + camera.north) / 2, (camera.west + camera.east) / 2))
                    .zoom(camera.zoom ?: 17.5).bearing(camera.bearing ?: 0.0).tilt(camera.tilt ?: 0.0)
                    .padding(0.0, h * 0.45, 0.0, 0.0) // vehicle in the lower part of the screen
                    .build()
                map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 900)
            } else if (camera.zoom != null) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng((camera.south + camera.north) / 2, (camera.west + camera.east) / 2), camera.zoom), 600)
            } else {
                val b = LatLngBounds.from(camera.north, camera.east, camera.south, camera.west)
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 60), 800)
            }
        }
    })
}
