#extension GL_OES_EGL_image_external : require
#extension GL_OES_standard_derivatives : enable

precision mediump float;
uniform samplerExternalOES cameraTexture;

varying vec2 v_CameraTextureCoordinate;
varying vec2 v_TextureCoordinate;

void derivImage( out vec4 fragColor, in vec2 fragCoord )
{
    vec2 uv = fragCoord.xy;
    vec4 colour = texture2D( cameraTexture, fragCoord );
    float gray = length( colour.rgb );
    fragColor = vec4( vec3( step( 0.06, length( vec2( dFdx( gray ), dFdy( gray ))))), 1.0f );
}

void main()
{
    derivImage( gl_FragColor, v_CameraTextureCoordinate );
}
