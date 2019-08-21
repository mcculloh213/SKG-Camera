// position
attribute vec4 vPosition;

// camera transform & texture
uniform mat4 mCameraTextureTransform;
attribute vec4 vCameraTextureCoordinate;

// tex coords
varying vec2 v_CameraTextureCoordinate;

void main()
{
    v_CameraTextureCoordinate = (mCameraTextureTransform * vCameraTextureCoordinate).xy;
    gl_Position = vPosition;
}
