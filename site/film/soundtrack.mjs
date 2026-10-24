// The film's soundtrack: a warm, gentle background track and soft interaction sounds on the film's cues
// (film.html), all synthesized here, with no samples. render.mjs calls soundtrack() and mixes it into the video.

/** The soundtrack as 16-bit stereo WAV bytes, [durationSeconds] long. */
export function soundtrack(durationSeconds) {
  const SR = 48000;
  const DURATION = durationSeconds;
  const N = SR * DURATION;
  const music = [new Float32Array(N), new Float32Array(N)];
  const sfx = [new Float32Array(N), new Float32Array(N)];

  // A repeatable random number in [0, 1).
  let seed = 7;
  const rand = () => { seed = (seed * 1664525 + 1013904223) % 4294967296; return seed / 4294967296; };
  const midi = (m) => 440 * Math.pow(2, (m - 69) / 12);
  const NOTE = { C: 0, 'C#': 1, Db: 1, D: 2, Eb: 3, E: 4, F: 5, 'F#': 6, G: 7, Ab: 8, A: 9, Bb: 10, B: 11 };
  const note = (name) => { const [, n, o] = name.match(/^([A-G][b#]?)(-?\d)$/); return midi(12 * (Number(o) + 1) + NOTE[n]); };

  /** Adds a voice to [bus] from [start] for [dur] seconds; [wave] gives a sample for time since start. */
  const add = (bus, start, dur, pan, wave) => {
    const s0 = Math.max(0, Math.floor(start * SR));
    const s1 = Math.min(N, Math.floor((start + dur) * SR));
    const l = Math.cos((pan + 1) * Math.PI / 4), r = Math.sin((pan + 1) * Math.PI / 4);
    for (let i = s0; i < s1; i++) {
      const v = wave((i - start * SR) / SR);
      bus[0][i] += v * l;
      bus[1][i] += v * r;
    }
  };
  const env = (t, attack, release, length) => Math.min(1, t / attack) * Math.min(1, Math.max(0, (length - t) / release));
  const TAU = Math.PI * 2;

  // ---- The background track ------------------------------------------------------------------------------------
  // 96 BPM, a bar per chord: Fmaj7, Am7, Dm9, Bbmaj7. Quiet pads and sparse plucks at first; bass, a soft pulse and
  // eighth-note plucks once the title lands; everything resolves onto F for the end card.
  const BEAT = 60 / 96;
  const BAR = BEAT * 4;
  const CHORDS = [
    ['F3', 'A3', 'C4', 'E4'],
    ['A3', 'C4', 'E4', 'G4'],
    ['D3', 'F3', 'A3', 'C4', 'E4'],
    ['Bb2', 'D3', 'F3', 'A3'],
  ];
  const ROOTS = ['F2', 'A2', 'D2', 'Bb1'];
  const TITLE = 11.2;
  const BUILD = 24.2;
  const END = 53.1;

  // Section level: soft intro, fuller from the title, fullest for the app build, then the end chord.
  const level = (t) => (t < TITLE ? 0.75 : t < BUILD ? 0.9 : t < 40 ? 0.95 : t < END ? 1 : 0.9);

  const pad = (start, length, freqs, amp) => {
    for (const f of freqs) {
      for (const detune of [-0.004, 0, 0.004]) {
        const ph = rand() * TAU;
        add(music, start, length + 1.2, (rand() - 0.5) * 0.6, (t) => {
          const e = env(t, 0.9, 1.2, length + 1.2);
          const vib = 1 + 0.0015 * Math.sin(TAU * 4.8 * t);
          const x = TAU * f * (1 + detune) * vib * t + ph;
          return amp * e * (Math.sin(x) + 0.18 * Math.sin(2 * x) + 0.06 * Math.sin(3 * x));
        });
      }
    }
  };

  // A soft, woody pluck, like a kalimba: a sine with a quickly fading overtone.
  const pluck = (start, f, amp, pan, decay = 0.9) => add(music, start, decay * 3, pan, (t) => {
    const e = Math.min(1, t / 0.004) * Math.exp(-t / decay * 3);
    return amp * e * (Math.sin(TAU * f * t) + 0.35 * Math.sin(TAU * 4.02 * f * t) * Math.exp(-t * 18) + 0.12 * Math.sin(TAU * 2 * f * t));
  });

  const bass = (start, f, amp, length) => add(music, start, length, 0, (t) => {
    const e = env(t, 0.03, 0.4, length);
    return amp * e * (Math.sin(TAU * f * t) + 0.25 * Math.sin(TAU * 2 * f * t));
  });

  const kick = (start, amp) => add(music, start, 0.4, 0, (t) => {
    const f = 45 + 70 * Math.exp(-t * 28);
    return amp * Math.exp(-t * 9) * Math.sin(TAU * f * t);
  });

  const shaker = (start, amp) => {
    let hp = 0, last = 0;
    add(music, start, 0.09, 0.3, (t) => {
      const n = rand() * 2 - 1;
      hp = 0.6 * (hp + n - last); last = n;
      return amp * hp * Math.min(1, t / 0.01) * Math.exp(-t * 45);
    });
  };

  const bars = Math.ceil(END / BAR);
  for (let b = 0; b < bars; b++) {
    const start = b * BAR;
    if (start >= END) break;
    const chord = CHORDS[b % 4];
    const length = Math.min(BAR, END - start);
    pad(start, length, chord.map(note), 0.022 * level(start));
    const tones = chord.map((n) => note(n) * 2);
    for (let s = 0; s < 8; s++) {
      const t = start + s * BEAT / 2;
      if (t >= END - 0.1) break;
      const eighths = t >= TITLE;
      if (!eighths && s % 2 === 1) continue;
      // Arpeggio up and back down the chord, with a high note on beat 3 now and then.
      const order = [0, 1, 2, 3, 2, 1, 2, 3];
      let f = tones[order[s] % tones.length];
      if (s === 4 && b % 2 === 1) f *= 2;
      pluck(t, f, (eighths ? 0.05 : 0.045) * level(t) * (s % 2 ? 0.75 : 1), s % 2 ? 0.35 : -0.35);
    }
    if (start >= TITLE - 0.01) {
      bass(start, note(ROOTS[b % 4]), 0.085 * level(start), BEAT * 2);
      bass(start + BEAT * 2, note(ROOTS[b % 4]), 0.065 * level(start), BEAT * 2);
    }
    if (start >= 15 - 0.01) {
      kick(start, 0.07); kick(start + BEAT * 2, 0.05);
    }
    if (start >= BUILD - 0.01) for (let k = 0; k < 4; k++) shaker(start + k * BEAT + BEAT / 2, 0.035);
  }
  // The end: everything lands on F, and rings out.
  pad(END + 0.15, 2.4, ['F3', 'A3', 'C4', 'E4', 'G4'].map(note), 0.026);
  bass(END + 0.15, note('F2'), 0.12, 2.6);
  ['F5', 'A5', 'C6', 'E6'].forEach((n, i) => pluck(END + 0.25 + i * 0.12, note(n), 0.05, i % 2 ? 0.4 : -0.4, 1.4));

  // ---- Interaction sounds --------------------------------------------------------------------------------------
  const pop = (t, f = 620, amp = 0.16) => add(sfx, t, 0.25, 0, (x) => {
    const freq = f * (0.65 + 0.6 * Math.min(1, x / 0.07));
    return amp * Math.min(1, x / 0.004) * Math.exp(-x * 22) * Math.sin(TAU * freq * x);
  });
  const tick = (t, amp = 0.05, f = 2300, pan = 0) => add(sfx, t, 0.06, pan, (x) => amp * Math.exp(-x * 90) * Math.sin(TAU * f * x));
  const chime = (t, notes, amp = 0.09) => notes.forEach((n, i) => add(sfx, t + i * 0.09, 2.2, i ? 0.2 : -0.2, (x) => {
    const f = note(n);
    const e = Math.min(1, x / 0.003);
    return amp * e * (Math.exp(-x * 2.4) * Math.sin(TAU * f * x) + 0.25 * Math.exp(-x * 6) * Math.sin(TAU * 2.76 * f * x) + 0.08 * Math.exp(-x * 10) * Math.sin(TAU * 5.4 * f * x));
  }));
  const whoosh = (t, dur = 0.6, amp = 0.05, from = -0.6, to = 0.6) => {
    let lp = 0;
    const s0 = Math.floor(t * SR), s1 = Math.min(N, Math.floor((t + dur) * SR));
    for (let i = s0; i < s1; i++) {
      const x = (i - s0) / (s1 - s0);
      const cutoff = 0.02 + 0.18 * Math.sin(Math.PI * x);
      lp += cutoff * ((rand() * 2 - 1) - lp);
      const v = amp * Math.sin(Math.PI * x) ** 2 * lp * 4;
      const pan = from + (to - from) * x;
      sfx[0][i] += v * Math.cos((pan + 1) * Math.PI / 4);
      sfx[1][i] += v * Math.sin((pan + 1) * Math.PI / 4);
    }
  };
  const tap = (t, amp = 0.16) => {
    add(sfx, t, 0.12, 0, (x) => amp * Math.exp(-x * 40) * Math.sin(TAU * (90 + 140 * Math.exp(-x * 60)) * x));
    add(sfx, t, 0.012, 0, () => amp * 0.35 * (rand() * 2 - 1));
  };
  const send = (t) => {
    add(sfx, t, 0.16, 0.25, (x) => 0.07 * Math.min(1, x / 0.005) * Math.exp(-x * 26) * Math.sin(TAU * (520 + 900 * x / 0.16) * x));
    tick(t + 0.03, 0.03, 3100, 0.25);
  };
  const success = (t, a = 'C6', b = 'G6') => { pluck(t, note(a), 0.07, -0.2, 0.5); pluck(t + 0.1, note(b), 0.07, 0.2, 0.7); };
  const sparkle = (t, dur, count, amp = 0.025) => {
    for (let i = 0; i < count; i++) {
      const at = t + rand() * dur;
      const f = 1800 + rand() * 3400;
      add(sfx, at, 0.25, rand() * 1.6 - 0.8, (x) => amp * Math.exp(-x * 26) * Math.sin(TAU * f * x));
    }
  };
  const thock = (t) => add(sfx, t, 0.2, 0, (x) => 0.06 * Math.exp(-x * 30) * Math.sin(TAU * (220 + 60 * Math.exp(-x * 40)) * x));

  // The film's cues (film.html), in order.
  pop(0.3, 520, 0.18);                                  // app icon
  chime(3.15, ['A5', 'E6']);                            // morning notification
  whoosh(6.5, 0.7, 0.05);                               // phone rises
  [7.3, 7.8, 9.1].forEach((t) => tick(t, 0.025, 1900));  // brief arrives
  whoosh(11.2, 0.6, 0.045, 0.6, -0.6);                  // title
  pop(11.5, 380, 0.12);
  whoosh(11.8, 1.2, 0.035, -0.8, 0.8);                  // the swoosh draws
  tick(12.0, 0.05, 2600);                               // beta pill
  whoosh(15.0, 0.6, 0.045);                             // back to the chat
  send(15.8);                                           // "Go for it!"
  [16.7, 18.2, 19.6].forEach((t) => tick(t, 0.02, 1600, -0.2));   // steps start
  [17.9, 19.3, 20.7].forEach((t) => tick(t, 0.045, 2500, 0.2));   // steps done
  [21.1, 22.2].forEach((t) => tick(t, 0.025, 1900));
  whoosh(24.25, 0.6, 0.045, -0.6, 0.6);                 // caption scene
  thock(24.5); thock(25.2);
  send(25.3);
  [26.3, 27.6].forEach((t) => tick(t, 0.02, 1600, 0.3));
  [27.2, 28.6].forEach((t) => tick(t, 0.045, 2500, 0.3));
  tick(29.3, 0.025, 1900, 0.3);
  whoosh(32.25, 0.6, 0.045, 0.6, -0.6);
  send(33.0);
  thock(32.6); thock(33.3);
  pop(33.9, 700, 0.09);                                 // approval card
  tap(36.7);                                            // Share
  success(37.3, 'E6', 'B6');                            // opened WhatsApp
  whoosh(40.2, 0.6, 0.045);
  send(40.9);
  [41.8, 43.0].forEach((t) => tick(t, 0.02, 1600));
  [42.7, 44.3].forEach((t) => tick(t, 0.045, 2500));
  pop(44.8, 660, 0.09);                                 // app card
  tap(46.2);                                            // Install
  success(47.4);                                        // installed
  whoosh(47.9, 0.6, 0.04, -0.4, 0.4);                   // home screen
  pop(48.65, 760, 0.14); pop(48.85, 980, 0.08);         // the icon lands
  chime(50.4, ['C6', 'G6', 'C7'], 0.08);                // paid back
  sparkle(50.5, 1.8, 46);                               // confetti
  pop(53.4, 600, 0.12);                                 // the beaver pops
  sparkle(53.45, 0.6, 10, 0.03);

  // ---- Mix -----------------------------------------------------------------------------------------------------
  // A small Schroeder reverb on a send, for warmth; the music sits under the interaction sounds.
  const reverb = (input) => {
    const out = [new Float32Array(N), new Float32Array(N)];
    const combs = [1557, 1617, 1491, 1422].map((d) => ({ d, buf: new Float32Array(d), i: 0 }));
    const allpasses = [225, 556].map((d) => ({ d, buf: new Float32Array(d), i: 0 }));
    for (let ch = 0; ch < 2; ch++) {
      combs.forEach((c, k) => { c.buf.fill(0); c.i = 0; c.d = c.d + ch * 23 * (k + 1); c.buf = new Float32Array(c.d); });
      allpasses.forEach((a) => { a.buf.fill(0); a.i = 0; });
      for (let n = 0; n < N; n++) {
        const x = input[ch][n];
        let y = 0;
        for (const c of combs) { const o = c.buf[c.i]; c.buf[c.i] = x + o * 0.82; c.i = (c.i + 1) % c.d; y += o; }
        y *= 0.25;
        for (const a of allpasses) { const o = a.buf[a.i]; const v = y + o * 0.5; a.buf[a.i] = v; a.i = (a.i + 1) % a.d; y = o - y * 0.5; }
        out[ch][n] = y;
      }
    }
    return out;
  };
  const reverbSend = [new Float32Array(N), new Float32Array(N)];
  for (let ch = 0; ch < 2; ch++) for (let n = 0; n < N; n++) reverbSend[ch][n] = music[ch][n] * 0.5 + sfx[ch][n] * 0.35;
  const wet = reverb(reverbSend);

  // The music dips a little whenever an interaction sound plays, so each one comes through without being loud.
  const duck = new Float32Array(N);
  let follow = 0;
  const attack = 1 - Math.exp(-1 / (0.005 * SR)), release = 1 - Math.exp(-1 / (0.3 * SR));
  for (let n = 0; n < N; n++) {
    const level = Math.max(Math.abs(sfx[0][n]), Math.abs(sfx[1][n]));
    follow += (level > follow ? attack : release) * (level - follow);
    duck[n] = 1 - 0.45 * Math.min(1, follow / 0.04);
  }

  const mix = [new Float32Array(N), new Float32Array(N)];
  const fadeIn = 0.4 * SR, fadeOut = 1.6 * SR;
  let peak = 0;
  for (let ch = 0; ch < 2; ch++) {
    for (let n = 0; n < N; n++) {
      const fade = Math.min(1, n / fadeIn, (N - n) / fadeOut);
      const v = (music[ch][n] * 0.8 * duck[n] + sfx[ch][n] * 1.15 + wet[ch][n] * 0.28) * fade;
      mix[ch][n] = Math.tanh(v * 1.4) / 1.4;
      peak = Math.max(peak, Math.abs(mix[ch][n]));
    }
  }
  const gain = 0.89 / peak;

  // 16-bit stereo WAV.
  const data = Buffer.alloc(N * 4);
  for (let n = 0; n < N; n++) {
    data.writeInt16LE(Math.round(Math.max(-1, Math.min(1, mix[0][n] * gain)) * 32767), n * 4);
    data.writeInt16LE(Math.round(Math.max(-1, Math.min(1, mix[1][n] * gain)) * 32767), n * 4 + 2);
  }
  const header = Buffer.alloc(44);
  header.write('RIFF', 0); header.writeUInt32LE(36 + data.length, 4); header.write('WAVE', 8);
  header.write('fmt ', 12); header.writeUInt32LE(16, 16); header.writeUInt16LE(1, 20); header.writeUInt16LE(2, 22);
  header.writeUInt32LE(SR, 24); header.writeUInt32LE(SR * 4, 28); header.writeUInt16LE(4, 32); header.writeUInt16LE(16, 34);
  header.write('data', 36); header.writeUInt32LE(data.length, 40);
  return Buffer.concat([header, data]);
}
