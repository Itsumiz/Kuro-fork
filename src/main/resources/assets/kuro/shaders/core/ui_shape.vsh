#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 q;
out vec2 gui;
out vec4 vData;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    q = UV0;                // -1..1 across the shape
    gui = Position.xy;      // GUI-space position, used to measure the shape in GUI px
    vData = Color;          // packed colour + parameter (see ShaderUi.pack)
}
