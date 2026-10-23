import sys, os
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
S, OUT = sys.argv[1], sys.argv[2]
COB='#0047AB'; MID='#8FB0E8'; DEEP='#0A1F4D'; CORAL='#FF6B4A'; INK='#0B1B3A'; W='#FFFFFF'
f = instantiateVariableFont(TTFont(f'{S}/fonts/Nunito.ttf'), {'wght': 900})
gs = f.getGlyphSet(); cmap = f.getBestCmap(); upm = f['head'].unitsPerEm
hmtx = f['hmtx']
def glyph(ch, x, scale, base):
    name = cmap[ord(ch)]
    pen = SVGPathPen(gs)
    gs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, x, base)))
    return pen.getCommands(), hmtx[name][0]*scale
# ---- icon 6 (Wau ringkas), refined: heading chevron, wings, moon, coral centre
def icon_inner(fg=W, accent=CORAL, wing_op='0.85'):
    return '<g transform="translate(0 11)">' + (f'<path d="M100 26 L166 75 L136 75 L100 49 L64 75 L34 75 Z" fill="{fg}"/>'
            f'<path d="M100 70 L152 105 L125 105 L100 88 L75 105 L48 105 Z" fill="{fg}" fill-opacity="{wing_op}"/>'
            f'<path d="M44 152 C68 122 132 122 156 152 C130 141 70 141 44 152 Z" fill="{fg}"/>'
            f'<circle cx="100" cy="124" r="11" fill="{accent}"/>') + '</g>'
def svg(w,h,body,label): return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" role="img" aria-label="{label}">{body}</svg>\n'
files = {
 'manotu-icon.svg': svg(200,200,f'<rect width="200" height="200" rx="44" fill="{COB}"/>'+icon_inner(),'Manotu icon: Wau ringkas'),
 'manotu-icon-square.svg': svg(200,200,f'<rect width="200" height="200" fill="{COB}"/>'+icon_inner(),'Manotu icon, square (iOS / store)'),
 # Android adaptive foreground: 108dp canvas, art inside the 66dp safe circle -> scale 0.6, centred
 'manotu-icon-android-foreground.svg': svg(108,108,f'<g transform="translate(24 24) scale(0.3)">'+icon_inner()+'</g>','Manotu adaptive icon foreground'),
 'manotu-icon-mono.svg': svg(108,108,f'<g transform="translate(24 24) scale(0.3)">'+icon_inner(fg='#000000',accent='#000000',wing_op='1')+'</g>','Manotu themed (monochrome) icon'),
}
# ---- wordmark E: "ma" cobalt, "n" + "tu" ink, "o" = destination ring + coral dot, coral intonation arc over "ma"
def wordmark(ink, ma, ring, dot, arc):
    size = 120; scale = size/upm; base = 150; x = 10; parts=[]; ma_start=x
    for ch,col in (('m',ma),('a',ma)):
        d,adv = glyph(ch,x,scale,base); parts.append(f'<path d="{d}" fill="{col}"/>'); x += adv
    ma_end = x
    d,adv = glyph('n',x,scale,base); parts.append(f'<path d="{d}" fill="{ink}"/>'); x += adv
    o_adv = hmtx[cmap[ord("o")]][0]*scale
    cx = x + o_adv/2; xh = f['OS/2'].sxHeight*scale; cy = base - xh/2; r = xh/2 - 9
    parts.append(f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r:.1f}" fill="none" stroke="{ring}" stroke-width="17"/>')
    parts.append(f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="8.5" fill="{dot}"/>')
    x += o_adv
    for ch in 'tu':
        d,adv = glyph(ch,x,scale,base); parts.append(f'<path d="{d}" fill="{ink}"/>'); x += adv
    top = base - f['OS/2'].sxHeight*scale - 22
    parts.insert(0, f'<path d="M{ma_start+6:.1f} {top+10:.1f} Q {(ma_start+ma_end)/2:.1f} {top-22:.1f} {ma_end-6:.1f} {top+10:.1f}" fill="none" stroke="{arc}" stroke-width="10" stroke-linecap="round"/>')
    return x+10, ''.join(parts)
w1, b1 = wordmark(INK, COB, COB, CORAL, CORAL)
w2, b2 = wordmark(W, W, W, CORAL, CORAL)
files['manotu-wordmark.svg'] = svg(round(w1),180,b1,'Manotu wordmark')
files['manotu-wordmark-white.svg'] = svg(round(w2),180,b2,'Manotu wordmark, white')
# lockup: icon + wordmark
files['manotu-lockup.svg'] = svg(round(w1)+230,200,f'<rect width="200" height="200" rx="44" fill="{COB}"/>'+icon_inner()+f'<g transform="translate(220 10)">{b1}</g>','Manotu logo')
for n,t in files.items(): open(os.path.join(OUT,n),'w').write(t)
print('\n'.join(files))
