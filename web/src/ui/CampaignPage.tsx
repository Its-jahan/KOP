// "Monthly plan" page: one big area driven over many days from a fixed start point.
import { useEffect, useMemo, useState } from 'react';
import type { GraphIndex } from '../core/graph';
import type { EdgeZoneInfo } from '../core/requirements';
import { confirmDay, newlyDriven, planCampaign, undoLastDay, type Campaign, type CampaignPlan } from '../core/campaign';
import type { Interval } from '../core/intervals';
import type { RequiredInterval } from '../core/types';
import type { StreetSet } from '../core/streets';
import { fmtMeters, fmtNum, type Lang, type Strings } from '../i18n/strings';

export interface CampaignContext {
  gi: GraphIndex;
  zoneInfo: EdgeZoneInfo[];
  requirements: RequiredInterval[];
}

export interface PendingReview {
  sessionId: string;
  label: string;
  driven: Map<string, Interval[]>;
}

export function CampaignPage({
  t, lang, campaign, ctx, canCreate, createHint, onCreate, onSave, onShowDay, onDriveDay, review, onDelete, streetSet, onPreset, onImportStreetSet,
}: {
  t: Strings;
  lang: Lang;
  campaign: Campaign | null;
  ctx: CampaignContext | null;
  canCreate: boolean;
  createHint: string | null;
  onCreate: (hoursPerDay: number, speedKmh: number, name: string) => void;
  onSave: (c: Campaign) => Promise<void>;
  onShowDay: (plan: CampaignPlan, dayIndex: number) => void;
  onDriveDay: (plan: CampaignPlan, dayIndex: number) => void;
  review: PendingReview | null;
  onDelete: () => void;
  streetSet: StreetSet | null;
  onPreset: (file: string) => void;
  onImportStreetSet: (f: File) => void;
}) {
  const [hours, setHours] = useState(4);
  const [speed, setSpeed] = useState(14);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [presets, setPresets] = useState<{ id: string; name: { fa: string; en: string }; file: string }[]>([]);
  useEffect(() => {
    if (campaign) return;
    fetch(`${import.meta.env.BASE_URL}presets/index.json`).then((r) => (r.ok ? r.json() : [])).then(setPresets).catch(() => setPresets([]));
  }, [campaign]);

  const plan = useMemo(() => {
    if (!campaign || !ctx) return null;
    return planCampaign(ctx.gi, ctx.zoneInfo, ctx.requirements, campaign, { planId: crypto.randomUUID(), createdAt: new Date().toISOString() });
  }, [campaign, ctx]);

  const totals = useMemo(() => {
    if (!campaign || !ctx) return null;
    const conf = new Map(campaign.confirmed);
    let total = 0, done = 0;
    for (const r of ctx.requirements) {
      if (r.status === 'needsReview') continue;
      total += r.lengthMeters;
      for (const [a, b] of conf.get(r.edgeId) ?? []) done += Math.max(0, Math.min(b, r.endOffsetMeters) - Math.max(a, r.startOffsetMeters));
    }
    return { total, done: Math.min(done, total) };
  }, [campaign, ctx]);

  const reviewReqs = useMemo(() => {
    if (!campaign || !ctx || !review) return [];
    return newlyDriven(ctx.requirements, new Map(campaign.confirmed), review.driven);
  }, [campaign, ctx, review]);
  const [unchecked, setUnchecked] = useState<Set<string>>(new Set());
  useEffect(() => setUnchecked(new Set()), [review?.sessionId]);

  if (!campaign) {
    return (
      <section data-testid="campaign-create">
        <p>{t.campaignIntro}</p>
        <div className="plan-card" data-testid="street-sets">
          <strong>{t.streetSetPresets}</strong>
          {presets.map((p) => (
            <button key={p.id} className={streetSet?.id === p.id ? 'on' : ''} onClick={() => onPreset(p.file)} data-testid={`preset-${p.id}`}>🛣 <bdi>{p.name[lang]}</bdi></button>
          ))}
          <label className="link small">{t.streetSetImport}
            <input type="file" accept=".json,.geojson,application/json" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) onImportStreetSet(f); e.target.value = ''; }} />
          </label>
          {streetSet && <p className="small ok">✓ {t.streetSet}: <bdi>{streetSet.name[lang]}</bdi> · {fmtNum(streetSet.lines.length, lang)}</p>}
          <p className="small muted">{t.streetSetHint}</p>
        </div>
        <label className="row">{t.campaignName}<input value={name} onChange={(e) => setName(e.target.value)} placeholder={t.campaignNamePh} /></label>
        <label className="row">{t.hoursPerDay}<input type="number" min={0.5} max={12} step={0.5} value={hours} onChange={(e) => setHours(Number(e.target.value))} data-testid="hours" /></label>
        <label className="row">{t.avgSpeed}<input type="number" min={5} max={40} step={1} value={speed} onChange={(e) => setSpeed(Number(e.target.value))} /></label>
        <button className="primary big" disabled={!canCreate || hours <= 0 || speed <= 0} onClick={() => onCreate(hours, speed, name.trim())} data-testid="campaign-create-btn">{t.campaignCreate}</button>
        {!canCreate && createHint && <p className="muted small">{createHint}</p>}
      </section>
    );
  }
  if (!ctx || !plan || !totals) return <p className="muted">{t.campaignLoading}</p>;
  const pct = totals.total > 0 ? (100 * totals.done) / totals.total : 0;
  const finish = new Date(Date.now() + Math.max(0, plan.days.length - 1) * 86400000);

  return (
    <section data-testid="campaign">
      <h3>{campaign.name}</h3>
      <div className="plan-card">
        <div className="kpis">
          <div><b>{fmtNum(pct, lang, 1)}%</b><span>{t.campaignDone}</span></div>
          <div><b>{fmtMeters(totals.done, t, lang)}</b><span>{t.confirmed}</span></div>
          <div><b>{fmtMeters(plan.remainingMeters, t, lang)}</b><span>{t.remaining}</span></div>
          <div><b>{fmtNum(plan.days.length, lang)}</b><span>{t.campaignDaysLeft}</span></div>
        </div>
        <p className="small muted">{t.campaignSettingsLine.replace('{h}', fmtNum(campaign.settings.hoursPerDay, lang, 1)).replace('{v}', fmtNum(campaign.settings.speedKmh, lang))} · {t.campaignFinish} {finish.toLocaleDateString(lang === 'fa' ? 'fa-IR' : 'en-GB')}</p>
        {campaign.streetSet && <p className="small muted">🛣 {t.streetSet}: <bdi>{campaign.streetSet.name[lang]}</bdi></p>}
        {plan.unplannable.length > 0 && <p className="small warn">{t.unreachable}: {fmtNum(plan.unplannable.length, lang)}</p>}
      </div>

      {review && (
        <div className="plan-card review" data-testid="campaign-review">
          <strong>{t.campaignReviewTitle} · {review.label}</strong>
          {reviewReqs.length === 0 ? <p className="small muted">{t.campaignReviewNone}</p> : (
            <>
              <p className="small">{t.campaignReviewText.replace('{n}', fmtNum(reviewReqs.length - unchecked.size, lang)).replace('{km}', fmtMeters(reviewReqs.filter((r) => !unchecked.has(r.requirementId)).reduce((a, r) => a + r.lengthMeters, 0), t, lang))}</p>
              <details className="small"><summary>{t.campaignReviewList}</summary>
                <ul className="review-list">
                  {groupByStreet(ctx.gi, reviewReqs).map(([street, rs]) => (
                    <li key={street}>
                      <label className="check">
                        <input type="checkbox" checked={rs.every((r) => !unchecked.has(r.requirementId))}
                          onChange={(e) => setUnchecked((u) => { const n = new Set(u); for (const r of rs) { if (e.target.checked) n.delete(r.requirementId); else n.add(r.requirementId); } return n; })} />
                        <bdi>{street}</bdi> · {fmtMeters(rs.reduce((a, r) => a + r.lengthMeters, 0), t, lang)}
                      </label>
                    </li>
                  ))}
                </ul>
              </details>
            </>
          )}
          <button className="go big" disabled={busy} data-testid="campaign-confirm" onClick={async () => {
            setBusy(true);
            const ok = reviewReqs.filter((r) => !unchecked.has(r.requirementId));
            await onSave(confirmDay(campaign, ok, review.driven, review.sessionId, new Date().toISOString()));
            setBusy(false);
          }}>✓ {t.campaignConfirm}</button>
        </div>
      )}

      <h3>{t.campaignDays}</h3>
      <ol className="days">
        {plan.days.map((d, i) => (
          <li key={d.day} className={i === 0 ? 'today' : ''} data-testid={`campaign-day-${d.day}`}>
            <div className="day-head">
              <strong>{i === 0 ? `${t.campaignToday} · ` : ''}{t.campaignDay} {fmtNum(campaign.history.length + d.day, lang)}</strong>
              <span className="small muted">{fmtMeters(d.totalMeters, t, lang)} · ~{fmtNum(Math.round(d.minutes / 6) / 10, lang, 1)} {t.hoursShort} · {fmtNum(d.targetIds.length, lang)} {t.campaignSegments}</span>
            </div>
            <div className="row">
              <button onClick={() => onShowDay(plan, i)}>🗺 {t.campaignShow}</button>
              {i === 0 && <button className="go" onClick={() => onDriveDay(plan, i)} data-testid="campaign-drive">▶ {t.campaignDriveToday}</button>}
            </div>
          </li>
        ))}
      </ol>
      {plan.days.length === 0 && <p className="ok">{t.campaignAllDone}</p>}

      {campaign.history.length > 0 && (
        <>
          <h3>{t.campaignHistory}</h3>
          <ul className="small">
            {campaign.history.map((h, i) => <li key={i}>{new Date(h.date).toLocaleDateString(lang === 'fa' ? 'fa-IR' : 'en-GB')} · {fmtMeters(h.confirmedMeters, t, lang)}</li>)}
          </ul>
          <button onClick={async () => { if (confirm(t.campaignUndoConfirm)) await onSave(undoLastDay(campaign, ctx.requirements)); }}>↶ {t.campaignUndo}</button>
        </>
      )}
      <p><button className="danger" onClick={() => { if (confirm(t.campaignDeleteConfirm)) onDelete(); }}>{t.campaignDelete}</button></p>
    </section>
  );
}

function groupByStreet(gi: GraphIndex, reqs: RequiredInterval[]): [string, RequiredInterval[]][] {
  const m = new Map<string, RequiredInterval[]>();
  for (const r of reqs) {
    const i = gi.idx.get(r.edgeId);
    const n = i === undefined ? r.edgeId : gi.edges[i].names.default ?? gi.edges[i].names.en ?? '—';
    m.set(n, [...(m.get(n) ?? []), r]);
  }
  return [...m.entries()].sort((a, b) => b[1].length - a[1].length);
}
