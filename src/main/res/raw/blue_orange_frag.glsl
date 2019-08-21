#extension GL_OES_EGL_image_external : require

precision mediump float;
uniform samplerExternalOES cameraTexture;

varying vec2 v_CameraTextureCoordinate;
varying vec2 v_TextureCoordinate;

void oranginator( out vec4 fragColor, in vec2 fragCoord )
{
    vec2 uv = fragCoord.xy;
    vec3 texture = texture2D( cameraTexture, uv ).rgb;
    float shade = dot(texture, vec3( 0.333333 ));
    vec3 colour = mix( vec3( 0.1, 0.36, 0.8 ) * (1.0 - 2.0 * abs( shade - 0.5 )), vec3( 1.06, 0.8, 0.55 ), 1.0 - shade);

    fragColor = vec4( colour, 1.0 );
}

void main()
{
    oranginator( gl_FragColor, v_CameraTextureCoordinate );
}
