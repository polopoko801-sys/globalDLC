import java.io.File;
import java.nio.file.Files;
import java.util.Base64;

/**
 * Buduje podgląd wyglądu wody z FloorWeather: jedną stronę HTML bez zależności, z teksturami wklejonymi
 * jako data: URI.
 *
 * <p>Shader w tej stronie liczy DOKŁADNIE to samo, co moduł: te same kafelki i dryfy, ten sam fresnel i
 * ten sam wykładnik odbicia, te same współczynniki alfy, ta sama kolejność warstw i ten sam podział na
 * jakość. Dzięki temu można obejrzeć efekt i pokręcić suwakami bez restartu Minecrafa.
 *
 * <p>Jedna świadoma różnica: moduł rysuje wodę jednym kaflem na blok, więc jego wygaszanie i kołysanie
 * zmieniają się co metr. Tutaj wszystko jest liczone na piksel, czyli jest to wygładzona wersja tego
 * samego obrazu - kształt warstw jest wierny, ziarno bloków nie.
 *
 * <p>Uruchomienie: {@code java tools/BuildWaterPreview.java <katalog zasobów> <plik wyjściowy>}
 */
public class BuildWaterPreview {
    public static void main(String[] args) throws Exception {
        File particles = new File(args.length > 0 ? args[0] : "src/main/resources/assets/gombal/textures/particles");
        File out = new File(args.length > 1 ? args[1] : "preview/floorweather.html");

        String water = dataUri(new File(particles, "fx_water.png"));
        String caustic = dataUri(new File(particles, "fx_water_caustic.png"));
        String sheen = dataUri(new File(particles, "fx_water_sheen.png"));
        String glint = dataUri(new File(particles, "fx_water_glint.png"));

        String html = TEMPLATE
                .replace("__WATER__", water)
                .replace("__CAUSTIC__", caustic)
                .replace("__SHEEN__", sheen)
                .replace("__GLINT__", glint);

        out.getParentFile().mkdirs();
        Files.writeString(out.toPath(), html);
        System.out.printf("zapisano %s  (%.0f kB)%n", out.getPath(), out.length() / 1024.0);
    }

    private static String dataUri(File png) throws Exception {
        if (!png.isFile()) throw new IllegalStateException("brak tekstury: " + png.getPath());
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(png.toPath()));
    }

    /** The page. Placeholders are text-replaced, so nothing here needs escaping. */
    private static final String TEMPLATE = """
<!doctype html>
<html lang="pl">
<head>
<meta charset="utf-8">
<title>FloorWeather — podgląd wody</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  html, body { margin: 0; height: 100%; overflow: hidden; background: #0b0d14; }
  body { font: 12px/1.45 system-ui, "Segoe UI", Roboto, sans-serif; color: #e7e9f0; display: flex; }
  #stage { position: relative; flex: 1 1 auto; min-width: 0; }
  canvas { display: block; width: 100%; height: 100%; }
  #hud { position: absolute; left: 12px; top: 10px; padding: 9px 12px; border-radius: 10px;
         background: rgba(14,16,24,.62); backdrop-filter: blur(6px); border: 1px solid rgba(255,255,255,.09);
         pointer-events: none; }
  #hud b { color: #b9a6ff; font-weight: 600; }
  #hud span { color: #9aa0b4; }
  aside { flex: 0 0 268px; padding: 14px; overflow-y: auto; background: #10121b;
          border-left: 1px solid rgba(255,255,255,.08); }
  h1 { font-size: 13px; margin: 0 0 4px; letter-spacing: .3px; }
  p.lead { margin: 0 0 12px; color: #8b90a4; }
  label { display: block; margin: 11px 0 2px; color: #cfd3e0; }
  label output { float: right; color: #b9a6ff; font-variant-numeric: tabular-nums; }
  input[type=range] { width: 100%; accent-color: #8b64f6; }
  select, .check { width: 100%; margin-top: 4px; background: #1a1d28; color: #e7e9f0;
                   border: 1px solid rgba(255,255,255,.12); border-radius: 6px; padding: 4px 6px; }
  .check { display: flex; align-items: center; gap: 7px; padding: 5px 6px; cursor: pointer; }
  .check input { accent-color: #8b64f6; }
  hr { border: 0; border-top: 1px solid rgba(255,255,255,.08); margin: 14px 0; }
  ul { margin: 6px 0 0; padding-left: 16px; color: #9aa0b4; }
  li.on { color: #86e0a0; }
  li.off { color: #5c6072; text-decoration: line-through; }
  .note { margin-top: 12px; color: #7f8496; }
</style>
</head>
<body>
<div id="stage">
  <canvas id="c"></canvas>
  <div id="hud">
    <b>FloorWeather</b> — <span id="fps">—</span><br>
    <span id="mode">High: korpus + kaustyka + fala + niebo + iskry ×2</span>
  </div>
</div>
<aside>
  <h1>Woda — podgląd</h1>
  <p class="lead">Ten sam shader co moduł: te same kafelki, dryfy, fresnel i kolejność warstw.</p>

  <label>TileSize <output id="oTile">4.0</output></label>
  <input type="range" id="tile" min="1.5" max="16" step="0.1" value="4">

  <label>Opacity <output id="oOp">165</output></label>
  <input type="range" id="opacity" min="10" max="255" step="1" value="165">

  <label>Detail <output id="oDetail">0.60</output></label>
  <input type="range" id="detail" min="0" max="1" step="0.01" value="0.6">

  <label>GrazingBoost <output id="oGraz">0.50</output></label>
  <input type="range" id="grazing" min="0" max="1.5" step="0.01" value="0.5">

  <label>WaveSpeed <output id="oSpeed">1.0</output></label>
  <input type="range" id="speed" min="0" max="4" step="0.05" value="1">

  <label>Radius <output id="oRadius">18</output></label>
  <input type="range" id="radius" min="2" max="48" step="1" value="18">

  <label>Jakość</label>
  <select id="quality">
    <option value="1">Low — tylko korpus wody</option>
    <option value="2">Medium — + kaustyka i drobna fala</option>
    <option value="3" selected>High — + niebo i iskry</option>
    <option value="4">Ultra — jak High (klimatskie)</option>
  </select>

  <label class="check"><input type="checkbox" id="glint" checked> Iskry na grzbietach</label>
  <label class="check"><input type="checkbox" id="grid" checked> Siatka bloków</label>
  <label class="check"><input type="checkbox" id="water" checked> Woda włączona</label>

  <hr>
  <div>Aktywne arkusze:</div>
  <ul id="sheets">
    <li id="s-caustic">kaustyka na dnie</li>
    <li id="s-body">korpus wody</li>
    <li id="s-ripple">drobna fala</li>
    <li id="s-sheen">odbicie nieba</li>
    <li id="s-glint">iskry</li>
    <li id="s-twinkle">drugie iskry</li>
  </ul>
  <p class="note">Moduł rysuje jeden kafel na blok, więc jego wygaszanie i kołysanie zmieniają się co metr —
  tutaj wszystko jest na piksel, czyli to jest wygładzona wersja tego samego obrazu.</p>
</aside>

<script>
const canvas = document.getElementById('c');
// preserveDrawingBuffer keeps the frame readable after it is composed, so the canvas can be copied out
// (a screenshot or a pixel probe) instead of coming back blank.
const gl = canvas.getContext('webgl2', {antialias: true, alpha: false, preserveDrawingBuffer: true, powerPreference: 'high-performance'});
if (!gl) document.body.innerHTML = '<p style="padding:20px">Ta przeglądarka nie ma WebGL2 — podgląd odpali się w Chromium.</p>';

const VERT = `#version 300 es
void main() {
  vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}`;

/* The module, in GLSL. Layer for layer, constant for constant. */
const FRAG = `#version 300 es
precision highp float;
out vec4 outColor;

uniform vec2  uRes;
uniform float uTime;
uniform float uTile, uOpacity, uDetail, uGrazing, uRadius;
uniform int   uQuality;
uniform float uGlint, uWater, uGrid;

uniform sampler2D uWaterTex, uCausticTex, uSheenTex, uGlintTex;

// White in the default mode: the textures carry the muddy browns, olives and warm specular themselves.
const float CAUSTIC_TINT_R = 1.0, CAUSTIC_TINT_G = 1.0, CAUSTIC_TINT_B = 1.0;
const float SHEEN_TINT_R   = 1.0, SHEEN_TINT_G   = 1.0, SHEEN_TINT_B   = 1.0;
const float GLINT_TINT_R   = 1.0, GLINT_TINT_G   = 1.0, GLINT_TINT_B   = 1.0;
const float WOBBLE = 0.035;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123); }
float vnoise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1, 0)), f.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), f.x), f.y);
}
float fbm(vec2 p) {
  float s = 0.0, a = 0.5;
  for (int i = 0; i < 4; i++) { s += a * vnoise(p); p *= 2.03; a *= 0.5; }
  return s;
}

/* The floor the water lies on: stone with a one-block grid, so you can see what the water does to it. */
vec3 ground(vec2 w) {
  float n = fbm(w * 1.7) * 0.55 + fbm(w * 0.5) * 0.45;
  vec3 base = mix(vec3(0.26, 0.23, 0.19), vec3(0.40, 0.37, 0.31), n);
  if (uGrid > 0.5) {
    vec2 g = abs(fract(w) - 0.5);
    float line = smoothstep(0.5, 0.47, max(g.x, g.y) + 0.5 - 0.5);
    float edge = 1.0 - smoothstep(0.0, 0.035, min(g.x, g.y));
    base = mix(base, base * 0.72, edge);
  }
  return base;
}

void main() {
  vec2 uv = gl_FragCoord.xy / uRes;
  vec2 ndc = uv * 2.0 - 1.0;
  float aspect = uRes.x / uRes.y;

  vec3 ro = vec3(0.0, 1.7, -0.0);
  vec3 rd = normalize(vec3(ndc.x * aspect * 0.95, ndc.y * 0.95 - 0.30, 1.0));

  // dusk-ish overcast: the reference is not a bright day, and a pale blue sky behind the water would
  // pull the whole picture back towards the blue look this is meant to avoid
  vec3 sky = mix(vec3(0.42, 0.40, 0.36), vec3(0.62, 0.58, 0.50), clamp(ndc.y * 0.5 + 0.5, 0.0, 1.0));
  float sun = pow(max(0.0, dot(rd, normalize(vec3(0.35, 0.62, 0.70)))), 96.0);
  sky += vec3(1.0, 0.93, 0.78) * sun * 0.65;

  if (rd.y >= -0.0005) { outColor = vec4(sky, 1.0); return; }

  float t = -ro.y / rd.y;
  vec2 w = ro.xz + rd.xz * t;                 // world position on the floor plane
  float dist = length(w);

  vec3 col = ground(w);

  // ---- FloorWeather, exactly as the module computes it --------------------------------
  float reach = uRadius;
  float edgeness = min(1.0, dist / (reach * 0.82));
  float strength = 1.0 - edgeness * edgeness * (3.0 - 2.0 * edgeness);
  float facing = min(1.0, dist / reach);

  if (uWater > 0.5 && strength > 0.04) {
    float fresnel = 0.86 + 0.42 * pow(facing, 1.4);
    float mirror  = pow(facing, 1.15);
    float sparkle = 0.15 + 0.85 * mirror;
    // glossy patches against matte ones, same smooth field the module uses
    float gloss   = 0.55 + 0.45 * sin(w.x * 0.11 + w.y * 0.07 + sin(w.y * 0.05) * 2.1);
    float level   = uOpacity * strength * fresnel;

    float tile = max(1.5, uTile);
    float causticTile = tile * 0.85;
    float rippleTile  = tile * 0.37;
    float sheenTile   = tile * 2.4;
    float glintTile   = tile * 1.6;
    float twinkleTile = glintTile * 0.61;

    float flow = uTime * 1.0;                 // WaveSpeed is baked into uTime by the page
    float causticU = flow * 0.030, causticV = flow * 0.019;
    float flowU    = flow * 0.075, flowV    = flow * 0.052;
    float rippleU  = -flow * 0.165, rippleV = flow * 0.115;
    float sheenU   = flow * 0.021, sheenV   = -flow * 0.013;
    float glintU   = -flow * 0.058, glintV  = flow * 0.087;

    float wobbleU = sin(w.x * 0.37 + w.y * 0.11 + flow * 1.05);
    float wobbleV = cos(w.x * 0.13 - w.y * 0.41 - flow * 0.87);

    bool normal = uQuality != 1;
    bool rich   = uQuality >= 3;
    bool doCaustic = normal && uDetail > 0.01;
    bool doRipple  = normal && uDetail > 0.01;
    bool doSheen   = rich;
    bool doGlint   = rich && uGlint > 0.5;

    float grazingBoost = (1.0 + uGrazing * facing) * sparkle;

    // layer 1: the light the surface focuses onto the bottom
    if (doCaustic) {
      float a = level * 0.34 * uDetail * (1.0 - 0.40 * facing) / 255.0;
      if (a > 3.0 / 255.0) {
        vec2 q = (w / causticTile + vec2(causticU, causticV) + vec2(wobbleU, wobbleV) * (WOBBLE * 0.7));
        vec4 s = texture(uCausticTex, q);
        col = mix(col, vec3(CAUSTIC_TINT_R, CAUSTIC_TINT_G, CAUSTIC_TINT_B) * s.rgb, clamp(s.a * a, 0.0, 1.0));
      }
    }
    // layer 2: the body
    {
      float a = level * 1.0 / 255.0;
      if (a > 3.0 / 255.0) {
        vec2 q = (w / tile + vec2(flowU, flowV) + vec2(wobbleU, wobbleV) * WOBBLE);
        vec4 s = texture(uWaterTex, q);
        col = mix(col, s.rgb, clamp(s.a * a, 0.0, 1.0));
      }
    }
    // layer 3: the fine chop sliding across the body
    if (doRipple) {
      float a = level * 0.30 * uDetail / 255.0;
      if (a > 3.0 / 255.0) {
        vec2 q = (w / rippleTile + vec2(rippleU, rippleV) + vec2(wobbleU, wobbleV) * 1.7);
        vec4 s = texture(uWaterTex, q);
        col = mix(col, s.rgb, clamp(s.a * a, 0.0, 1.0));
      }
    }
    // layer 4: the sky, where the surface is seen at a grazing angle
    if (doSheen) {
      float a = level * 0.72 * mirror * gloss / 255.0;
      if (a > 3.0 / 255.0) {
        vec2 q = (w / sheenTile + vec2(sheenU, sheenV) + vec2(wobbleU, wobbleV) * (WOBBLE * 0.5));
        vec4 s = texture(uSheenTex, q);
        col = mix(col, vec3(SHEEN_TINT_R, SHEEN_TINT_G, SHEEN_TINT_B) * s.rgb, clamp(s.a * a, 0.0, 1.0));
      }
    }
    // layers 5 and 6: the sparks, twice, on unrelated grids
    if (doGlint) {
      float a = level * 0.70 * grazingBoost / 255.0;
      if (a > 3.0 / 255.0) {
        vec2 q = (w / glintTile + vec2(glintU, glintV) + vec2(wobbleU, wobbleV) * 1.4);
        vec4 s = texture(uGlintTex, q);
        col = mix(col, vec3(GLINT_TINT_R, GLINT_TINT_G, GLINT_TINT_B) * s.rgb, clamp(s.a * a, 0.0, 1.0));
      }
      float b = level * 0.42 * grazingBoost / 255.0;
      if (b > 3.0 / 255.0) {
        vec2 q = (w / twinkleTile - vec2(glintU, glintV) * 1.6 + vec2(wobbleU, wobbleV) * 2.1);
        vec4 s = texture(uGlintTex, q);
        col = mix(col, vec3(GLINT_TINT_R, GLINT_TINT_G, GLINT_TINT_B) * s.rgb, clamp(s.a * b, 0.0, 1.0));
      }
    }
  }

  // haze into the skyline so the far edge of the patch does not cut against a hard horizon
  float horizon = smoothstep(0.0, 0.28, -rd.y);
  col = mix(sky, col, horizon);
  outColor = vec4(col, 1.0);
}`;

function compile(type, src) {
  const s = gl.createShader(type);
  gl.shaderSource(s, src); gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
  return s;
}
const prog = gl.createProgram();
gl.attachShader(prog, compile(gl.VERTEX_SHADER, VERT));
gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, FRAG));
gl.linkProgram(prog);
if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog));
gl.useProgram(prog);

const aniso = gl.getExtension('EXT_texture_filter_anisotropic');
function textureFrom(src, unit) {
  const tex = gl.createTexture();
  gl.activeTexture(gl.TEXTURE0 + unit);
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([255, 255, 255, 255]));
  const img = new Image();
  img.onload = () => {
    gl.activeTexture(gl.TEXTURE0 + unit);
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);
    gl.generateMipmap(gl.TEXTURE_2D);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    if (aniso) {
      const max = gl.getParameter(aniso.MAX_TEXTURE_MAX_ANISOTROPY_EXT);
      gl.texParameterf(gl.TEXTURE_2D, aniso.TEXTURE_MAX_ANISOTROPY_EXT, Math.min(8, max));
    }
  };
  img.src = src;
  return tex;
}

const TEX = {
  water:   textureFrom('__WATER__', 0),
  caustic: textureFrom('__CAUSTIC__', 1),
  sheen:   textureFrom('__SHEEN__', 2),
  glint:   textureFrom('__GLINT__', 3)
};

const U = (n) => gl.getUniformLocation(prog, n);
const ui = {
  tile: document.getElementById('tile'), opacity: document.getElementById('opacity'),
  detail: document.getElementById('detail'), grazing: document.getElementById('grazing'),
  speed: document.getElementById('speed'), radius: document.getElementById('radius'),
  quality: document.getElementById('quality'), glint: document.getElementById('glint'),
  grid: document.getElementById('grid'), water: document.getElementById('water')
};
const out = {
  tile: document.getElementById('oTile'), opacity: document.getElementById('oOp'),
  detail: document.getElementById('oDetail'), grazing: document.getElementById('oGraz'),
  speed: document.getElementById('oSpeed'), radius: document.getElementById('oRadius')
};

function sheetState() {
  const q = parseInt(ui.quality.value, 10);
  const normal = q !== 1, rich = q >= 3;
  const detail = parseFloat(ui.detail.value) > 0.01;
  const glint = ui.glint.checked && rich;
  const map = {
    caustic: normal && detail,
    body: true,
    ripple: normal && detail,
    sheen: rich,
    glint: glint,
    twinkle: glint
  };
  for (const key of Object.keys(map)) {
    const li = document.getElementById('s-' + key);
    li.className = map[key] ? 'on' : 'off';
  }
  const names = { 1: 'Low: sam korpus', 2: 'Medium: korpus + kaustyka + fala',
                  3: 'High: korpus + kaustyka + fala + niebo + iskry ×2',
                  4: 'Ultra: jak High' };
  document.getElementById('mode').textContent = names[q];
}

function readInputs() {
  out.tile.value = parseFloat(ui.tile.value).toFixed(1);
  out.opacity.value = ui.opacity.value;
  out.detail.value = parseFloat(ui.detail.value).toFixed(2);
  out.grazing.value = parseFloat(ui.grazing.value).toFixed(2);
  out.speed.value = parseFloat(ui.speed.value).toFixed(1);
  out.radius.value = ui.radius.value;
  sheetState();
}
Object.values(ui).forEach(el => el.addEventListener('input', readInputs));
readInputs();

function resize() {
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const w = Math.max(320, Math.floor(canvas.clientWidth * dpr));
  const h = Math.max(240, Math.floor(canvas.clientHeight * dpr));
  if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
}
window.addEventListener('resize', resize);

let simTime = 0, last = performance.now(), frames = 0, fpsAt = last;
function frame(now) {
  const dt = Math.min(0.05, (now - last) / 1000); last = now;
  // WaveSpeed 0 freezes the pattern in the module, so it must freeze it here too
  simTime += dt * parseFloat(ui.speed.value);

  resize();
  gl.viewport(0, 0, canvas.width, canvas.height);
  gl.useProgram(prog);

  gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, TEX.water);
  gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, TEX.caustic);
  gl.activeTexture(gl.TEXTURE2); gl.bindTexture(gl.TEXTURE_2D, TEX.sheen);
  gl.activeTexture(gl.TEXTURE3); gl.bindTexture(gl.TEXTURE_2D, TEX.glint);
  gl.uniform1i(U('uWaterTex'), 0);
  gl.uniform1i(U('uCausticTex'), 1);
  gl.uniform1i(U('uSheenTex'), 2);
  gl.uniform1i(U('uGlintTex'), 3);

  gl.uniform2f(U('uRes'), canvas.width, canvas.height);
  gl.uniform1f(U('uTime'), simTime);
  gl.uniform1f(U('uTile'), parseFloat(ui.tile.value));
  gl.uniform1f(U('uOpacity'), parseFloat(ui.opacity.value));
  gl.uniform1f(U('uDetail'), parseFloat(ui.detail.value));
  gl.uniform1f(U('uGrazing'), parseFloat(ui.grazing.value));
  gl.uniform1f(U('uRadius'), parseFloat(ui.radius.value));
  gl.uniform1i(U('uQuality'), parseInt(ui.quality.value, 10));
  gl.uniform1f(U('uGlint'), ui.glint.checked ? 1 : 0);
  gl.uniform1f(U('uWater'), ui.water.checked ? 1 : 0);
  gl.uniform1f(U('uGrid'), ui.grid.checked ? 1 : 0);

  gl.drawArrays(gl.TRIANGLES, 0, 3);

  frames++;
  if (now - fpsAt > 500) {
    document.getElementById('fps').textContent = Math.round(frames * 1000 / (now - fpsAt)) + ' fps';
    frames = 0; fpsAt = now;
  }
  requestAnimationFrame(frame);
}
resize();
requestAnimationFrame(frame);
</script>
</body>
</html>
""";
}
