// Spoken instructions with the browser's speech synthesis. Persian if the device has a
// Persian voice and the UI is Persian, otherwise English. Each maneuver is announced at most
// once per band: ~300 m, ~100 m and at the turn.
import type { NavInstruction } from '../core/navigator';
import { STRINGS, type Lang } from '../i18n/strings';

export class Voice {
  private spoken = new Set<string>();
  private lastKey = '';
  enabled = true;
  constructor(private lang: Lang) {}

  setLang(l: Lang) {
    this.lang = l;
  }

  stop() {
    try {
      speechSynthesis.cancel();
    } catch {
      /* unsupported */
    }
  }

  announce(n: NavInstruction | null) {
    if (!this.enabled || !n || typeof speechSynthesis === 'undefined') return;
    if (n.key !== this.lastKey) {
      this.lastKey = n.key;
      this.spoken = new Set([...this.spoken].filter((k) => k.startsWith(n.key + '|')));
    }
    const d = n.distanceMeters;
    const band = n.mode === 'to_road' ? 'road' : d <= 30 ? 'now' : d >= 70 && d <= 140 ? '100' : d >= 220 && d <= 400 ? '300' : null;
    if (!band) return;
    const id = `${n.key}|${band}`;
    if (this.spoken.has(id)) return;
    this.spoken.add(id);
    const voices = speechSynthesis.getVoices();
    const fa = this.lang === 'fa' ? voices.find((v) => v.lang.toLowerCase().startsWith('fa')) : undefined;
    const lang: Lang = fa ? 'fa' : 'en';
    const t = STRINGS[lang];
    const act = n.mode === 'to_road' ? t.navHeadToRoad
      : n.type === 'join' ? t.navJoinRoute
      : n.type === 'turnaround' && !n.turnaroundVerified ? t.turnaroundUnverified
      : ((t as unknown as Record<string, string>)[n.type] ?? t.continue);
    const road = n.roadName && n.mode !== 'to_road' ? ` ${t.onto} ${n.roadName}` : '';
    const text = band === 'now' || band === 'road' ? act + road : t.voiceInMeters.replace('{m}', band).replace('{a}', act + road);
    const u = new SpeechSynthesisUtterance(text);
    u.lang = lang === 'fa' ? 'fa-IR' : 'en-US';
    if (fa) u.voice = fa;
    speechSynthesis.cancel();
    speechSynthesis.speak(u);
  }
}
