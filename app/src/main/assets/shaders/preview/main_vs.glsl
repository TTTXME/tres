// The viewfinder's quad: the frame's uv turned to the screen (x and y swapped, then x flipped), the corners rotated.
in vec2 vPosition, vTexCoord;
uniform mat4 uTexRotateMatrix;
out vec2 texCoord;
void main() {
    texCoord = vec2(1.0 - vTexCoord.y, vTexCoord.x);
    gl_Position = uTexRotateMatrix * vec4(vPosition, 0.0, 1.0);
}
