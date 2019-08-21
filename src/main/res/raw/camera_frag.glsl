#extension GL_OES_EGL_image_external : require

precision mediump float;
uniform samplerExternalOES cameraTexture;

varying vec2 v_CameraTextureCoordinate;
varying vec2 v_TextureCoordinate;

void main()
{
    vec4 cameraColor = texture2D( cameraTexture, v_CameraTextureCoordinate );
    gl_FragColor = cameraColor;
}
