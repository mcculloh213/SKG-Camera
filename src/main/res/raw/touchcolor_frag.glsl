#extension GL_OES_EGL_image_external : require

precision mediump float;
uniform samplerExternalOES cameraTexture;

varying vec2 v_CameraTextureCoordinate;
varying vec2 v_TextureCoordinate;

uniform float offsetR;
uniform float offsetG;
uniform float offsetB;

void main()
{
    vec4 cameraColor = texture2D( cameraTexture, v_CameraTextureCoordinate );
    gl_FragColor = cameraColor * vec4( offsetR, offsetG, offsetB, 1. );
}
