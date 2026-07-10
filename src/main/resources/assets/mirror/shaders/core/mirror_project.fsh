#version 330

uniform sampler2D InSampler;

in vec4 vClip;

out vec4 fragColor;

void main() {
    // Projective texturing: each surface point carries its position as projected through the VIRTUAL
    // camera (vClip). Perspective-divide to NDC, map to [0,1] UV, and sample the reflection FBO there.
    vec2 uv = (vClip.xy / vClip.w) * 0.5 + 0.5;

    // Minification anti-aliasing. When the mirror is distant or grazing, each screen pixel covers MANY
    // reflection texels and a single tap shimmers/aliases the reflected detail. fwidth(uv) is this
    // fragment's texture footprint (uv change across one pixel); box-average a 3x3 grid of LINEAR taps
    // spanning that footprint (+/-0.5*fwidth, 0.5*fwidth spacing), anisotropic via the per-axis fwidth
    // so it also handles grazing angles. (Manual because Blaze3D exposes no mipmap generation.)
    vec2 tap = fwidth(uv) * 0.5;
    vec2 texel = 1.0 / vec2(textureSize(InSampler, 0));
    vec3 c;
    if (all(lessThan(tap, texel))) {
        // Sub-texel footprint (near/full-screen mirrors): the 3x3 taps would all land inside one texel
        // and average to the same value, so take one tap and skip 8 samples of bandwidth.
        c = texture(InSampler, uv).rgb;
    } else {
        c = vec3(0.0);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                c += texture(InSampler, uv + vec2(i, j) * tap).rgb;
            }
        }
        c /= 9.0;
    }

    // Opaque: occlusion by closer geometry is the depth test's job, not alpha. The sky/horizon pass
    // leaves alpha~0 in a band in the reflection FBO; honoring it would blend in the world behind the
    // mirror (the horizon gap). Pin alpha to 1 so the surface always shows the reflected scene/fog.
    fragColor = vec4(c, 1.0);
}
