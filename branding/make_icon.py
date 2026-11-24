# One shape list -> Android vector drawables + branding/logo.svg, so they can't drift apart.
import sys
# Usage: make_icon.py RES_DIR BRANDING_DIR PREVIEW_DIR   (PREVIEW_DIR gets mono.svg)
INK='#101C2B';GOLD='#E9B44C';CREAM='#FFF6E0';ORANGE='#F2842F';SPOT='#B97F22';W='#FFFFFF';STEEL='#E6EEF6'
c=lambda x,y,r:f'M{x-r:g},{y:g} a{r:g},{r:g} 0 1,0 {2*r:g},0 a{r:g},{r:g} 0 1,0 {-2*r:g},0z'
BODY='M31,46 C36,37 46,34.5 55,34.5 C68,34.5 76,45 76,57 C76,70 66,79 54,79 C44,79 36,76 32,69.5 C36,68 39.5,65 41,60 C40,54 36,49 31,46Z'
TAIL='M73,53 Q83,44 85,47 Q82,54 85,63 Q82,67 73,60Z'
TOP=['M46,37 L48,28 L53,35.5Z','M57,35.5 L62,28.5 L64.5,38Z','M67,40 L74.5,36 L73,46Z']
BOT=['M44,75 L43,83 L50,78.5Z','M55,78.5 L58,86 L63,77Z','M67,73.5 L74,78.5 L73,68Z']
FIN='M56,58 Q66,56 67,62.5 Q65,69 57,66 Q60,62 56,58Z'
T1='M31.5,46.3 L30.5,53.5 Q33.5,55.5 36.8,53.3 L37.3,50.5Z';T2='M33,69 L32.5,62.5 Q35.8,60.3 38.8,62.3 L38.6,65.5Z'
HOOK='M26,10 V57.5 a4,4 0 0,0 8,0 v-2.2 l-2.4,2.2'
S=[] # (d, fill, stroke, width, evenodd)
add=lambda d,f=None,s=None,w=0,eo=False:S.append((d,f,s,w,eo))
add(TAIL,ORANGE,INK,2)
for d in TOP:add(d,GOLD,INK,2)
for d in BOT:add(d,CREAM,INK,2)
add(BODY,GOLD)
add('M41,60 C52,64 66,64 75.6,60 C74,72 65,79 54,79 C44,79 36,76 32,69.5 C36,68 39.5,65 41,60Z',CREAM)
for a in((64,43,2.4),(69,49.5,1.8),(61,50,1.5)):add(c(*a),SPOT)
add(BODY,None,INK,2.4)
add(T1,W,INK,1.8);add(T2,W,INK,1.8);add(FIN,ORANGE,INK,2)
add(c(47,45,8.5),W,INK,2);add(c(43.2,47.5,3.7),INK);add(c(42,46.3,1.2),W)
add(HOOK,None,INK,4.4);add(HOOK,None,STEEL,1.6)
M=[]
mono=lambda d,f='#FFFFFF',s=None,w=0,eo=False:M.append((d,f,s,w,eo))
mono(TAIL)
for d in TOP+BOT:mono(d)
mono(BODY+' '+c(47,45,7.5),eo=True);mono(c(43.6,47.3,3.6))
mono(HOOK,None,'#FFFFFF',2.6)
def vd(L):
    o='<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="108dp" android:height="108dp"\n    android:viewportWidth="108" android:viewportHeight="108">\n'
    for d,f,s,w,e in L:
        a=[f'android:pathData="{d}"']
        if f:a.append(f'android:fillColor="{f}"')
        if e:a.append('android:fillType="evenOdd"')
        if s:a+= [f'android:strokeColor="{s}"',f'android:strokeWidth="{w}"','android:strokeLineCap="round"','android:strokeLineJoin="round"']
        o+='    <path '+' '.join(a)+' />\n'
    return o+'</vector>\n'
def svg(L,bg,vb='18 18 72 72'):
    o=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}" width="512" height="512"><rect width="108" height="108" fill="{bg}"/>\n'
    for d,f,s,w,e in L:
        o+=f'<path d="{d}" fill="{f or "none"}"'+(' fill-rule="evenodd"' if e else '')+(f' stroke="{s}" stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round"' if s else '')+'/>\n'
    return o+'</svg>\n'
res,brand,tmp=sys.argv[1:4]
open(res+'/drawable/ic_launcher_foreground.xml','w').write(vd(S))
open(res+'/drawable/ic_launcher_monochrome.xml','w').write(vd(M))
open(brand+'/logo.svg','w').write(svg(S,'#8EC1E6'))
open(tmp+'/mono.svg','w').write(svg(M,'#37474F'))
