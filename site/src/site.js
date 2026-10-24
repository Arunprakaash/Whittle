// The landing page's motion: the beaver (played from the app's own sprite, the way SpriteImage.kt plays it),
// the to-do list it chews through, and the looping vignettes. Content comes from site.json via #site-config.
(() => {
  const config = JSON.parse(document.getElementById('site-config').textContent);
  const still = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // ---- Sprites -------------------------------------------------------------------------------------------

  /** Screen pixels per art pixel; .beaver in styles.css is 16 × 14 art pixels at this size. */
  const PIXEL = 7;
  /** Room around the sprite for particles to fly into, in art pixels: left/right, top, bottom. */
  const ROOM = { side: 8, top: 10, bottom: 2 };
  // These match SpriteImage.kt so the beaver moves the same as in the app.
  const FADE_FROM = 0.45;
  const JITTER = 0.16;
  const BITE_SPEED = 1.4;
  const BITE_WIDEN = 0.7;
  const SEAM = 0.35;

  /** Each frame as runs of one colour, the way the app draws it. */
  const runsOf = (sprite) => Object.fromEntries(Object.entries(sprite.frames).map(([name, rows]) => {
    const runs = [];
    rows.forEach((row, y) => {
      for (let x = 0; x < row.length;) {
        let end = x + 1;
        while (end < row.length && row[end] === row[x]) end++;
        if (row[x] !== '.') runs.push({ x, y, length: end - x, color: sprite.palette[row[x]] });
        x = end;
      }
    });
    return [name, runs];
  }));

  /** A frame as a pixel-art SVG data URI, for small images like Chai Timer's icon. */
  const frameUri = (sprite, rows) => {
    const rects = runsOf({ palette: sprite.palette, frames: { only: rows } }).only
      .map((run) => `<rect x="${run.x}" y="${run.y}" width="${run.length}" height="1" fill="${run.color}"/>`).join('');
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${sprite.width} ${sprite.height}" shape-rendering="crispEdges">${rects}</svg>`;
    return `data:image/svg+xml,${encodeURIComponent(svg)}`;
  };

  const easeOutBack = (t, overshoot) => 1 + (overshoot + 1) * (t - 1) ** 3 + overshoot * (t - 1) ** 2;

  /** Plays a sprite's poses on a canvas inside host, crossfading between poses like the app. */
  class SpritePlayer {
    constructor(host, sprite) {
      this.sprite = sprite;
      this.runs = runsOf(sprite);
      this.particles = [];
      const width = sprite.width + 2 * ROOM.side;
      const height = sprite.height + ROOM.top + ROOM.bottom;
      const ratio = window.devicePixelRatio || 1;
      this.canvas = document.createElement('canvas');
      this.canvas.width = width * PIXEL * ratio;
      this.canvas.height = height * PIXEL * ratio;
      Object.assign(this.canvas.style, {
        position: 'absolute',
        left: `${-ROOM.side * PIXEL}px`,
        top: `${-ROOM.top * PIXEL}px`,
        width: `${width * PIXEL}px`,
        height: `${height * PIXEL}px`,
      });
      this.context = this.canvas.getContext('2d');
      this.context.scale(ratio * PIXEL, ratio * PIXEL);
      this.context.translate(ROOM.side, ROOM.top);
      host.replaceChildren(this.canvas);
      this.active = null;
      this.previous = null;
    }

    play(name, now) {
      if (this.active?.name === name) return;
      this.previous = this.active && { ...this.active, until: now + this.sprite.crossfadeMillis };
      this.active = { name, pose: this.sprite.poses[name], start: now, lastBite: -1 };
      const particles = this.active.pose.particles;
      if (particles?.on === 'start') this.emit(particles, now);
    }

    firstFrame(pose) {
      return pose.steps?.[0]?.frame ?? pose.bite.rest;
    }

    look(active, t) {
      const pose = active.pose;
      let frame = this.firstFrame(pose);
      let dip = 0;
      let scaleX = 1;
      let scaleY = 1;
      if (pose.steps?.length) {
        let at = t % pose.steps.reduce((sum, step) => sum + step.millis, 0);
        for (const step of pose.steps) {
          if (at < step.millis) { frame = step.frame; break; }
          at -= step.millis;
        }
      }
      if (pose.breathe) {
        const wave = Math.sin(2 * Math.PI * t / pose.breathe.periodMillis);
        scaleY += pose.breathe.amount * wave;
        scaleX -= pose.breathe.amount * 0.5 * wave;
      }
      if (pose.bite) {
        const bite = pose.bite;
        const phase = (t % bite.millis) / bite.millis;
        frame = phase < bite.biteAt ? bite.rest : bite.bite;
        const into = phase < bite.biteAt ? 0 : (phase - bite.biteAt) / (1 - bite.biteAt);
        const bump = Math.sin(Math.PI * Math.min(1, into * BITE_SPEED));
        dip = bite.dip * bump;
        scaleY *= 1 - bite.squash * bump;
        scaleX *= 1 + bite.squash * BITE_WIDEN * bump;
      }
      if (pose.pop) {
        const progress = Math.min(1, t / pose.pop.millis);
        const scale = pose.pop.from + (1 - pose.pop.from) * easeOutBack(progress, pose.pop.overshoot);
        scaleX *= scale;
        scaleY *= scale;
      }
      return { frame, dip, scaleX, scaleY };
    }

    paint(look, alpha) {
      const [pivotX, pivotY] = this.sprite.pivot;
      const still = look.dip === 0 && look.scaleX === 1 && look.scaleY === 1;
      const seam = still ? 0 : SEAM;
      const c = this.context;
      c.save();
      c.globalAlpha = alpha;
      c.translate(pivotX, pivotY);
      c.scale(look.scaleX, look.scaleY);
      c.translate(-pivotX, -pivotY + look.dip);
      for (const run of this.runs[look.frame]) {
        c.fillStyle = run.color;
        c.fillRect(run.x, run.y, run.length + seam / PIXEL, 1 + seam / PIXEL);
      }
      c.restore();
    }

    emit(spec, now) {
      const range = spec.angles[1] - spec.angles[0];
      for (let index = 0; index < spec.count; index++) {
        const share = spec.count === 1 ? 0.5 : index / (spec.count - 1);
        const degrees = spec.angles[0] + range * share + range * JITTER * (Math.random() - 0.5);
        const radians = degrees * Math.PI / 180;
        const speed = spec.speed[0] + (spec.speed[1] - spec.speed[0]) * Math.random();
        const color = this.sprite.palette[spec.keys[Math.floor(Math.random() * spec.keys.length)]];
        this.particles.push({ x: spec.from[0], y: spec.from[1], vx: Math.cos(radians) * speed, vy: Math.sin(radians) * speed, gravity: spec.gravity, born: now, life: spec.lifeMillis, color });
      }
    }

    draw(now) {
      const active = this.active;
      const t = now - active.start;
      const bite = active.pose.bite;
      const particles = active.pose.particles;
      if (bite && particles?.on === 'bite') {
        const cycle = Math.floor(t / bite.millis);
        if ((t % bite.millis) / bite.millis >= bite.biteAt && cycle !== active.lastBite) {
          active.lastBite = cycle;
          this.emit(particles, now);
        }
      }
      const c = this.context;
      c.clearRect(-ROOM.side, -ROOM.top, this.sprite.width + 2 * ROOM.side, this.sprite.height + ROOM.top + ROOM.bottom);
      const fade = this.previous && now < this.previous.until ? (this.previous.until - now) / this.sprite.crossfadeMillis : 0;
      if (fade > 0) this.paint(this.look(this.previous, now - this.previous.start), fade);
      else this.previous = null;
      this.paint(this.look(active, t), 1 - fade);
      this.particles = this.particles.filter((particle) => now - particle.born < particle.life);
      for (const particle of this.particles) {
        const age = (now - particle.born) / 1000;
        const progress = (now - particle.born) / particle.life;
        c.globalAlpha = Math.min(1, Math.max(0, 1 - (progress - FADE_FROM) / (1 - FADE_FROM)));
        c.fillStyle = particle.color;
        const x = particle.x + particle.vx * age;
        const y = particle.y + particle.vy * age + 0.5 * particle.gravity * age * age;
        c.fillRect(x - 0.5, y - 0.5, 1, 1);
      }
      c.globalAlpha = 1;
    }
  }

  // ---- Clock and visibility ------------------------------------------------------------------------------

  const ticks = [];
  const onFrame = (tick) => ticks.push(tick);
  const loop = (now) => {
    for (const tick of ticks) tick(now);
    requestAnimationFrame(loop);
  };

  /** Calls onEnter each time element scrolls into view, and reports whether it's on screen. */
  const watch = (element, onEnter) => {
    const state = { visible: false };
    new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting && !state.visible) onEnter(performance.now());
      state.visible = entry.isIntersecting;
    }, { threshold: 0.25 }).observe(element);
    return state;
  };

  /** Shows or hides element while keeping its space, so vignettes don't jump; showing replays its entrance. */
  const show = (element, visible) => {
    if (element.classList.contains('off') === !visible) return;
    element.classList.toggle('off', !visible);
    if (!visible) return;
    const name = `entering${element.dataset.enter ? `-${element.dataset.enter}` : ''}`;
    element.classList.remove(name);
    void element.offsetWidth;
    element.classList.add(name);
  };

  /** Elements with data-from / data-until (ms into the loop) appear and leave on cue; data-press-* press them. */
  const cue = (root, t) => {
    root.querySelectorAll('[data-from]').forEach((element) => {
      const until = element.dataset.until ? Number(element.dataset.until) : Infinity;
      show(element, t >= Number(element.dataset.from) && t < until);
    });
    root.querySelectorAll('[data-press-from]').forEach((element) => {
      element.classList.toggle('pressed', t >= Number(element.dataset.pressFrom) && t < Number(element.dataset.pressUntil));
    });
  };

  /** A vignette that restarts when it scrolls into view and loops every loopMillis while on screen. */
  const vignette = (root, loopMillis, update) => {
    if (!root || still) return;
    let start = 0;
    const view = watch(root, (now) => { start = now; });
    update(0);
    onFrame((now) => { if (view.visible) update((now - start) % loopMillis, now); });
  };

  // ---- The beavers ---------------------------------------------------------------------------------------

  const beaverSprite = config.sprites.beaver;
  const heroHost = document.querySelector('[data-beaver="hero"]');
  const outroHost = document.querySelector('[data-beaver="outro"]');
  const hero = new SpritePlayer(heroHost, beaverSprite);
  const outro = new SpritePlayer(outroHost, beaverSprite);
  const t0 = performance.now();
  hero.play('idle', t0);
  outro.play('idle', t0);

  // ---- The to-do list ------------------------------------------------------------------------------------

  /** Each step of a chore takes this long; the first chore starts on its own after FIRST_MILLIS. */
  const STEP_MILLIS = 950;
  const FIRST_MILLIS = 1600;
  const BETWEEN_MILLIS = 1400;
  /** How long the beaver celebrates a finished chore: the done pose's own length. */
  const doneMillis = beaverSprite.poses.done.steps.reduce((sum, step) => sum + step.millis, 0);

  const chores = config.chores;
  const buttons = [...document.querySelectorAll('[data-chore]')];
  const count = document.querySelector('[data-count]');
  const note = document.querySelector('[data-pad-note]');
  const newList = document.querySelector('[data-new-list]');
  // The list is on screen only when the film isn't (styles.css); hidden, it neither plays nor animates the beaver.
  const listShown = getComputedStyle(document.querySelector('[data-pad]')).display !== 'none';
  const list = { active: -1, start: 0, done: new Set(), auto: !still && listShown, next: t0 + FIRST_MILLIS, doneAt: -Infinity };

  /**
   * Each chore has one status line whose space is kept from the start, so the list never changes height: it shows the
   * step being worked on, then the chore's result. Showing new words replays the line's entrance.
   */
  const showLine = (button, text, done) => {
    const line = button.querySelector('[data-line]');
    if (line.dataset.text === text) return;
    line.dataset.text = text;
    line.className = `chore-line ${done ? 'receipt' : 'stepline'}`;
    const icon = line.querySelector('[data-line-icon]');
    icon.className = done ? 'ms fill' : 'ms spin';
    icon.textContent = done ? 'check_circle' : 'progress_activity';
    line.querySelector('[data-line-text]').textContent = text;
    line.style.animation = 'none';
    void line.offsetWidth;
    line.style.animation = '';
  };

  const hideLine = (button) => {
    const line = button.querySelector('[data-line]');
    line.className = 'chore-line off';
    line.dataset.text = '';
  };

  const status = () => {
    const left = chores.length - list.done.size;
    count.textContent = left ? `${left} to go` : 'all done';
    note.textContent = !left ? 'Nothing left. Go outside.' : list.active >= 0 ? 'Whittle is on it…' : 'Tap any chore.';
    newList.hidden = left > 0;
  };

  const finish = (index, now) => {
    const button = buttons[index];
    button.classList.remove('active');
    button.setAttribute('aria-pressed', 'true');
    showLine(button, chores[index].steps.at(-1), true);
    list.done.add(index);
    list.active = -1;
    list.doneAt = now;
    list.next = now + BETWEEN_MILLIS;
    status();
  };

  const begin = (index, now) => {
    list.active = index;
    list.start = now;
    buttons[index].classList.add('active');
    status();
  };

  buttons.forEach((button, index) => button.addEventListener('click', () => {
    if (list.done.has(index) || list.active >= 0) return;
    list.auto = false;
    if (still) finish(index, performance.now());
    else begin(index, performance.now());
  }));

  newList.addEventListener('click', () => {
    list.done.clear();
    buttons.forEach((button) => {
      button.setAttribute('aria-pressed', 'false');
      hideLine(button);
    });
    list.auto = !still;
    list.next = performance.now() + BETWEEN_MILLIS / 2;
    status();
  });

  if (listShown) onFrame((now) => {
    if (list.active >= 0) {
      const steps = chores[list.active].steps;
      const elapsed = now - list.start;
      if (elapsed >= steps.length * STEP_MILLIS) finish(list.active, now);
      else showLine(buttons[list.active], steps[Math.floor(elapsed / STEP_MILLIS)], false);
    } else if (list.auto && now >= list.next) {
      const next = chores.findIndex((_, index) => !list.done.has(index));
      if (next >= 0) begin(next, now);
    }
    if (still) return;
    hero.play(list.active >= 0 ? 'working' : now - list.doneAt < doneMillis ? 'done' : 'idle', now);
    hero.draw(now);
  });
  status();

  // The beaver at the bottom celebrates every so often.
  const OUTRO_MILLIS = 4200;
  if (still) {
    hero.draw(t0);
    outro.draw(t0);
  } else {
    onFrame((now) => {
      outro.play((now - t0) % OUTRO_MILLIS < doneMillis ? 'done' : 'idle', now);
      outro.draw(now);
    });
  }

  // ---- The film ------------------------------------------------------------------------------------------

  // Plays muted while on screen; the button turns the sound on, from the top the first time so nothing is missed.
  const film = document.querySelector('[data-film]');
  if (film && !still) {
    const video = film.querySelector('video');
    const sound = film.querySelector('[data-film-sound]');
    let heard = false;
    sound.addEventListener('click', () => {
      video.muted = !video.muted;
      if (!video.muted && !heard) {
        heard = true;
        video.currentTime = 0;
      }
      video.play().catch(() => {});
      sound.setAttribute('aria-pressed', String(!video.muted));
      sound.querySelector('.ms').textContent = video.muted ? 'volume_off' : 'volume_up';
      sound.querySelector('[data-film-sound-label]').textContent = video.muted ? sound.dataset.off : sound.dataset.on;
    });
    new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting) video.play().catch(() => {});
      else video.pause();
    }, { threshold: 0.25 }).observe(video);
  }

  // ---- Vignettes -----------------------------------------------------------------------------------------

  const phone = document.querySelector('[data-demo="phone"]');
  vignette(phone, 9000, (t) => cue(phone, t));

  // Chai Timer: builds with the network off, then lands on the home screen once Install is pressed.
  const chai = config.sprites.chai;
  const chaiFrames = Object.values(chai.frames).map((rows) => frameUri(chai, rows));
  const chaiImages = document.querySelectorAll('[data-chai]');
  const app = document.querySelector('[data-demo="app"]');
  const BUILD = { from: 700, millis: 3300, pressFrom: 5200, pressUntil: 5550, landAt: 5600 };
  vignette(app, 9000, (t, now = performance.now()) => {
    const progress = Math.max(0, Math.min(100, (t - BUILD.from) / BUILD.millis * 100));
    app.querySelector('[data-progress]').style.width = `${progress}%`;
    app.querySelector('[data-status]').textContent = progress < 100 ? 'Building… network off' : 'Version 1 · Uses vibration only';
    show(app.querySelector('[data-install]'), progress >= 100);
    app.querySelector('[data-install]').classList.toggle('pressed', t >= BUILD.pressFrom && t < BUILD.pressUntil);
    app.querySelectorAll('[data-log]').forEach((line) => { line.style.visibility = progress >= Number(line.dataset.log) ? 'visible' : 'hidden'; });
    const landed = app.querySelector('[data-landed]');
    if (landed.hidden === t >= BUILD.landAt) {
      landed.hidden = t < BUILD.landAt;
      if (!landed.hidden) { landed.classList.remove('entering-land'); void landed.offsetWidth; landed.classList.add('entering-land'); }
    }
    const frame = chaiFrames[Math.floor(now / chai.steamMillis) % chaiFrames.length];
    chaiImages.forEach((image) => { if (image.getAttribute('src') !== frame) image.src = frame; });
  });

  // Research: types a question, sources pop in, then the answer. "Ask something else" moves on.
  const TYPE_MILLIS = 45;
  const research = document.querySelector('[data-demo="research"]');
  const typed = research.querySelector('[data-typed]');
  const sources = research.querySelector('[data-sources]');
  const answer = research.querySelector('[data-answer]');
  let question = 0;
  let shownSources = -1;
  const sourceChip = ([icon, name]) => {
    const chip = document.createElement('span');
    chip.className = 'src entering';
    const glyph = document.createElement('span');
    glyph.className = 'ms';
    glyph.textContent = icon;
    chip.append(glyph, name);
    return chip;
  };
  let researchStart = 0;
  if (!still) {
    const view = watch(research, (now) => { researchStart = now; });
    research.querySelector('[data-next]').addEventListener('click', () => {
      question = (question + 1) % config.questions.length;
      researchStart = performance.now();
    });
    onFrame((now) => {
      if (!view.visible) return;
      const item = config.questions[question];
      const t = (now - researchStart) % 8000;
      typed.textContent = item.q.slice(0, Math.floor(t / TYPE_MILLIS));
      const typedAt = item.q.length * TYPE_MILLIS + 300;
      const count = item.sources.filter((_, i) => t > typedAt + i * 450).length;
      if (count !== shownSources) {
        sources.replaceChildren(...item.sources.slice(0, count).map(sourceChip));
        shownSources = count;
      }
      answer.textContent = item.answer;
      show(answer, t > typedAt + item.sources.length * 450 + 400);
    });
  } else {
    research.querySelector('[data-next]').hidden = true;
  }

  // Linux: commands type out, their output appears.
  const terminal = document.querySelector('[data-demo="terminal"]');
  const lines = [...terminal.children];
  vignette(terminal, 9000, (t) => {
    let left = t;
    config.terminal.forEach(([kind, text], index) => {
      const line = lines[index];
      const typing = kind === 'prompt';
      line.style.visibility = left > 0 ? 'visible' : 'hidden';
      line.textContent = typing ? text.slice(0, Math.max(0, Math.floor(left / TYPE_MILLIS))) || ' ' : text;
      left -= typing ? text.length * TYPE_MILLIS + 500 : 350;
    });
  });

  // Night shift: the clock ticks over, then the brief arrives.
  const night = document.querySelector('[data-demo="night"]');
  vignette(night, 6500, (t) => {
    night.querySelector('[data-time]').textContent = t < 2200 ? '7:29' : '7:30';
    cue(night, t);
  });

  // Models: the choice moves on its own until the visitor picks one.
  const MODEL_MILLIS = 2200;
  const models = [...document.querySelectorAll('[data-model]')];
  let picked = -1;
  const choose = (index) => models.forEach((model, i) => model.setAttribute('aria-checked', String(i === index)));
  models.forEach((model, index) => model.addEventListener('click', () => { picked = index; choose(index); }));
  if (!still) onFrame((now) => { if (picked < 0) choose(Math.floor(now / MODEL_MILLIS) % models.length); });

  requestAnimationFrame(loop);
})();
