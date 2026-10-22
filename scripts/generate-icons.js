const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const iconsDir = path.join(__dirname, '..', 'extension', 'icons');
fs.mkdirSync(iconsDir, { recursive: true });

function createPng(width, height, drawFn) {
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  
  function createChunk(type, data) {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length, 0);
    const typeBuf = Buffer.from(type, 'ascii');
    const body = Buffer.concat([typeBuf, data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body), 0);
    return Buffer.concat([len, body, crc]);
  }

  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) {
      c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    }
    table[i] = c >>> 0;
  }
  function crc32(buf) {
    let c = 0xffffffff;
    for (let i = 0; i < buf.length; i++) {
      c = table[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
    }
    return ((c ^ 0xffffffff) >>> 0);
  }

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6; // RGBA
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;
  const ihdrChunk = createChunk('IHDR', ihdr);

  const rowBytes = 1 + width * 4;
  const rawData = Buffer.alloc(rowBytes * height);
  for (let y = 0; y < height; y++) {
    const offset = y * rowBytes;
    rawData[offset] = 0;
    for (let x = 0; x < width; x++) {
      const pOffset = offset + 1 + x * 4;
      const [r, g, b, a] = drawFn(x, y, width, height);
      rawData[pOffset] = r;
      rawData[pOffset + 1] = g;
      rawData[pOffset + 2] = b;
      rawData[pOffset + 3] = a;
    }
  }

  const idatData = zlib.deflateSync(rawData);
  const idatChunk = createChunk('IDAT', idatData);
  const iendChunk = createChunk('IEND', Buffer.alloc(0));

  return Buffer.concat([signature, ihdrChunk, idatChunk, iendChunk]);
}

function iconPainter(x, y, w, h) {
  const cx = w / 2;
  const cy = h / 2;
  const dx = x - cx;
  const dy = y - cy;
  const dist = Math.sqrt(dx * dx + dy * dy);
  const outerR = w * 0.46;
  const innerR = w * 0.28;

  if (dist > outerR) return [0, 0, 0, 0];

  // Gradient background from indigo (#6366f1) to fuchsia (#d946ef)
  const t = (x + y) / (w + h);
  const rBg = Math.round(99 + (217 - 99) * t);
  const gBg = Math.round(102 + (70 - 102) * t);
  const bBg = Math.round(241 + (239 - 241) * t);

  // Center symbol: stopwatch / pause bar symbol
  const barW = Math.max(1, Math.round(w * 0.09));
  const barH = Math.round(h * 0.36);
  const spacing = Math.max(1, Math.round(w * 0.08));
  
  const inBar1 = (x >= cx - spacing - barW && x <= cx - spacing && y >= cy - barH/2 && y <= cy + barH/2);
  const inBar2 = (x >= cx + spacing && x <= cx + spacing + barW && y >= cy - barH/2 && y <= cy + barH/2);

  if (inBar1 || inBar2) {
    return [255, 255, 255, 255];
  }

  // Ring meter around the symbol
  if (dist >= innerR && dist <= outerR * 0.88) {
    const angle = Math.atan2(dy, dx);
    if (angle < 0.6) {
      return [255, 255, 255, 220];
    }
  }

  return [rBg, gBg, bBg, 255];
}

for (const size of [16, 48, 128]) {
  const png = createPng(size, size, iconPainter);
  fs.writeFileSync(path.join(iconsDir, `icon-${size}.png`), png);
  console.log(`Generated icon-${size}.png: ${png.length} bytes`);
}
