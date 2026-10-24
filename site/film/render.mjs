#!/usr/bin/env node
// Renders the landing page's film from film.html: every frame in headless Chromium, the synthesized soundtrack
// (soundtrack.mjs), mixed to web loudness with ffmpeg. Writes site/src/media/whittle-film.mp4 and its poster.
//
//   cd site/film && npm install && node render.mjs           the film and its poster
//   node render.mjs stills 4,13.5,36                         single frames into site/film/stills/, to check
//
// Needs ffmpeg on the PATH and a Chromium: set FILM_CHROME to its executable, or have Chrome installed.

import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { chromium } from 'playwright-core';
import { soundtrack } from './soundtrack.mjs';

const here = new URL('./', import.meta.url);
const media = new URL('../src/media/', here);
const [mode, times] = process.argv.slice(2);

/** When the poster is taken: the title card, with the wordmark and swoosh drawn. */
const POSTER_SECONDS = 13.6;

const sprites = {
  beaver: JSON.parse(await readFile(new URL('../../docs/design/sprites/beaver.sprite.json', here), 'utf8')),
  chai: JSON.parse(await readFile(new URL('../src/chai.sprite.json', here), 'utf8')),
};
const work = await mkdtemp(join(tmpdir(), 'whittle-film-'));
const page_ = join(work, 'film.html');
const html = (await readFile(new URL('film.html', here), 'utf8')).replace('/*SPRITES*/', JSON.stringify(sprites).replace(/</g, '\\u003c'));
await writeFile(page_, html);

const browser = await chromium.launch(process.env.FILM_CHROME ? { executablePath: process.env.FILM_CHROME } : { channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
page.on('pageerror', (error) => { throw error; });
await page.goto(`file://${page_}`);
await page.waitForFunction(() => window.filmReady === true, null, { timeout: 30000 });
const { FPS, DURATION } = await page.evaluate(() => window.FILM);
const frameAt = async (seconds, options) => {
  await page.evaluate((t) => window.renderAt(t), seconds);
  return page.screenshot(options);
};

const run = (args, input) => new Promise((resolve, reject) => {
  const ffmpeg = spawn('ffmpeg', ['-y', '-loglevel', 'error', ...args], { stdio: [input ? 'pipe' : 'ignore', 'inherit', 'inherit'] });
  ffmpeg.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`ffmpeg exited ${code}`))));
  if (input) input(ffmpeg.stdin);
});

if (mode === 'stills') {
  const out = new URL('stills/', here);
  await mkdir(out, { recursive: true });
  for (const t of times.split(',').map(Number)) await frameAt(t, { path: new URL(`${t}.png`, out).pathname });
} else {
  await mkdir(media, { recursive: true });
  const silent = join(work, 'silent.mp4');
  const frames = Math.round(FPS * DURATION);
  await run(['-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'mjpeg', '-i', '-', '-c:v', 'libx264', '-preset', 'slow', '-crf', '20', '-pix_fmt', 'yuv420p', silent], async (stdin) => {
    for (let i = 0; i < frames; i++) {
      const jpeg = await frameAt(i / FPS, { type: 'jpeg', quality: 92 });
      if (!stdin.write(jpeg)) await new Promise((resolve) => stdin.once('drain', resolve));
      if (i % (FPS * 10) === 0) console.log(`frame ${i}/${frames}`);
    }
    stdin.end();
  });
  const wav = join(work, 'soundtrack.wav');
  await writeFile(wav, soundtrack(DURATION));
  await run(['-i', silent, '-i', wav, '-map', '0:v', '-map', '1:a', '-c:v', 'copy', '-af', 'loudnorm=I=-16:TP=-1.5:LRA=11', '-ar', '48000',
    '-c:a', 'aac', '-b:a', '160k', '-movflags', '+faststart', '-shortest', new URL('whittle-film.mp4', media).pathname]);
  await frameAt(POSTER_SECONDS, { path: new URL('whittle-film-poster.jpg', media).pathname, type: 'jpeg', quality: 85 });
  console.log('wrote site/src/media/whittle-film.mp4 and whittle-film-poster.jpg');
}
await browser.close();
await rm(work, { recursive: true, force: true });
