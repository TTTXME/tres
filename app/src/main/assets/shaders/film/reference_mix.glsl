precision highp float; precision highp sampler2D; precision highp sampler3D;
#define FILM_LUT 0
#define FILM_TO_P3 0
uniform sampler2D InputBuffer;
uniform sampler2D FilmBuffer;
uniform float strengthU;
#if FILM_LUT == 1
uniform sampler3D FilmLut;
uniform vec2 lutU;
uniform vec3 lutMinU;
uniform vec3 lutScaleU;
#endif
out vec4 Output;
#if FILM_TO_P3 == 1
vec3 decodeSrgb(vec3 c){return mix(c/12.92,pow((max(c,0.0)+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),c));}
vec3 encodeSrgb(vec3 c){return mix(12.92*c,1.055*pow(max(c,0.0),vec3(1.0/2.4))-0.055,step(vec3(0.0031308),c));}
#endif
void main(){
    ivec2 xy=ivec2(gl_FragCoord.xy);
    vec3 film=texelFetch(FilmBuffer,xy,0).rgb;
#if FILM_LUT == 1
    vec3 c=clamp((clamp(film,0.0,1.0)-lutMinU)*lutScaleU,0.0,1.0);
    vec3 l=texture(FilmLut,c*((lutU.y-1.0)/lutU.y)+0.5/lutU.y).rgb;
    film=mix(film,l,clamp(lutU.x,0.0,1.0));
#endif
#if FILM_TO_P3 == 1
    const mat3 SRGB_TO_P3=mat3(0.82259287,0.03319951,0.01708535,
        0.17753395,0.96678350,0.07239572,0.0,0.0,0.91030148);
    film=encodeSrgb(SRGB_TO_P3*decodeSrgb(film));
#endif
    vec3 colour=strengthU>=1.0?film:mix(texelFetch(InputBuffer,xy,0).rgb,film,strengthU);
    Output=vec4(clamp(colour,0.0,1.0),1.0);
}
