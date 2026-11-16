import assert from 'node:assert/strict';
import { createBody } from '../app/src/main/assets/bluefin/kit/body.js';

class Element {
  children = [];
  attributes = {};
  ownerDocument = document;
  setAttribute(name, value) { this.attributes[name] = value; }
  appendChild(element) { this.children.push(element); }
  set innerHTML(value) {
    if (value.startsWith('<ellipse')) this.children = [new Element(), new Element(), new Element()];
  }
}

const document = {
  createElement: () => new Element(),
  createElementNS: () => new Element(),
};
document.head = new Element();
const root = new Element();
const events = [];
const body = createBody({
  root,
  bounds: () => ({ W: 393, H: 852, floorY: 812, S: 0.85 }),
  emit: (kind, event) => events.push({ type: kind, ...event }),
  sound() {},
}, { figure: { draw() {} }, roam: 'off' });

body.do('jump');
for (let i = 0; i < 180; i++) body.step(1 / 60);
body.do('sit');
for (let i = 0; i < 84; i++) body.step(1 / 60);
body.do('sleep');
for (let i = 0; i < 60; i++) body.step(1 / 60);
const height = body.layout().box.h;
for (let i = 0; i < 300; i++) {
  body.step([0.08, 0.1, 0.14, 2][i % 4]);
  const { box } = body.layout();
  assert.ok(Number.isFinite(box.y) && Math.abs(box.h - height) < 6, JSON.stringify(box));
}

body.do('stand');
for (let i = 0; i < 120; i++) body.step(1 / 60);
const { x, y } = body.layout().hit[0];
body.pointer('down', { x, y, t: 0 });
body.pointer('move', { x: x + 12, y: y - 12, t: 550 });
body.step(1 / 60);
assert.equal(body.layout().mode, 'drag');
body.pointer('move', { x: x + 100, y: y - 40, t: 600 });
body.step(1 / 60);
body.pointer('cancel', { x: x + 100, y: y - 40, t: 610 });
assert.equal(body.layout().pressing, false);
assert.equal(body.layout().mode, 'air');
const before = body.layout().x;
body.step(1 / 60);
assert.equal(body.layout().x, before);
assert.ok(!events.some(event => event.kind === 'throw'));
console.log('Animation and cancelled drag regressions passed');
