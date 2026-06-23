#version 330

// Projective-textured mirror surface. The quad is drawn in the MAIN view (CornersMain) and each point
// is also projected through the VIRTUAL camera (CornersVirtual) to sample the reflection FBO. Both
// corner sets are pre-subtracted against their camera on the CPU in double precision, so the quad
// lands bit-exactly where the terrain renders (no precision drift at large world coordinates).
layout(std140) uniform MirrorProj {
    mat4 MainVP;             // mainProj * mainViewRotation
    mat4 VirtualVP;          // mainProj * virtualViewRotation
    vec4 CornersMain[4];     // mirror front-face corners relative to the main camera
    vec4 CornersVirtual[4];  // mirror front-face corners relative to the virtual camera
};

out vec4 vClip;

const int IDX[6] = int[6](0, 1, 2, 2, 1, 3);

void main() {
    int i = IDX[gl_VertexID];
    gl_Position = MainVP * vec4(CornersMain[i].xyz, 1.0);
    // Constant depth-buffer-space bias toward the camera so the surface WINS the composite's depth test against
    // the mirror's coplanar glass block face at ANY distance. 26.2 is REVERSE-Z (near = 1, far = 0) and the
    // composite tests GREATER_THAN_OR_EQUAL, so "toward the camera" is a LARGER NDC z: ADD bias*w (the 26.1
    // build subtracted it for the old LEQUAL test). Distance-independent, unlike a world-space nudge which
    // compresses to nothing far out. Kept SMALL: the glass is exactly coplanar so the bias only needs to clear
    // depth-buffer quantization (~1e-7). Reverse-Z keeps good far precision, so this fixed bias never beats real
    // occluders in front of the mirror far out (which would break occlusion) while still killing the z-fight stipple.
    gl_Position.z += 0.00001 * gl_Position.w;
    vClip = VirtualVP * vec4(CornersVirtual[i].xyz, 1.0);
}
