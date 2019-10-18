package ktx.sovereign.camera.contract

import android.util.Size
import android.view.Surface
import ktx.sovereign.camera.util.NormalizedFace

interface CameraSpec {
    /**
     * Return preview size to use pass thru from camera API.
     */
    fun getPreviewSize(): Size

    /**
     * Get camera field of view, in degrees. Entry 0 is horizontal, entry 1 is vertical FOV.
     */
    fun getFieldOfView(): FloatArray

    /**
     * Get the camera sensor orientation relative to device native orientation
     * Typically 90 or 270 for phones, 0 or 180 for tablets, though many tables are also
     * portrait-native.
     */
    fun getOrientation(): Int

    /**
     * Open the camera. Call startPreview() to actually see something.
     */
    fun openCamera()

    /**
     * Start preview to a surface. Also need to call openCamera().
     * @param surface
     */
    fun startPreview(surface: Surface)

    /**
     * Close the camera.
     */
    fun closeCamera()

    /**
     * Take a picture and return data with provided callback.
     * Preview must be started.
     */
    fun takePicture()

    /**
     * Set whether we are continuously taking pictures, or not.
     */
    fun setBurst(go: Boolean)

    /**
     * Take a picture and return data with provided callback.
     * Preview must be started.
     */
    fun setCallback(callback: MyCameraCallback)

    /**
     * Is a raw stream available.
     */
    fun isRawAvailable(): Boolean

    /**
     * Is a reprocessing available.
     */
    fun isReprocessingAvailable(): Boolean

    /**
     * Triggers an AF scan. Leaves camera in AUTO.
     */
    fun triggerAFScan()

    /**
     * Runs CAF (continuous picture).
     */
    fun setCAF()

    /**
     * Camera picture callbacks.
     */
    interface MyCameraCallback {
        /**
         * What text to display on the Edge and NR mode buttons.
         */
        fun setNoiseEdgeText(s1: String, s2: String)

        /**
         * What text to display on the Edge and NR mode buttons (reprocessing flow).
         */
        fun setNoiseEdgeTextForReprocessing(s1: String, s2: String)

        /**
         * Full size JPEG is available.
         * @param jpegData
         * @param x
         * @param y
         */
        fun jpegAvailable(jpegData: ByteArray, x: Int, y: Int)

        /**
         * Metadata from an image frame.
         *
         * @param info Info string we print just under viewfinder.
         *
         * fps, mLastIso, af, ae, awb
         * @param faces Face coordinates.
         * @param normExposure Exposure value normalized from 0 to 1.
         * @param normLensPos Lens position value normalized from 0 to 1.
         * @param fps
         * @param iso
         * @param afState
         * @param aeState
         * @param awbState
         */
        fun frameDataAvailable(
            faces: Array<NormalizedFace>,
            normExposure: Float, normLensPos: Float, fps: Float, iso: Int,
            afState: Int, aeState: Int, awbState: Int
        )

        /**
         * Misc performance data.
         */
        fun performanceDataAvailable(timeToFirstFrame: Int?, halWaitTime: Int?, droppedFrameCount: Float?)

        /**
         * Called when camera2 FULL not available.
         */
        fun noCamera2Full()

        /**
         * Used to set the preview SurfaceView background color from black to transparent.
         */
        fun receivedFirstFrame()
    }

    fun setCaptureFlow(
        yuv1: Boolean?, yuv2: Boolean?, raw10: Boolean?,
        nr: Boolean?, edge: Boolean?, face: Boolean?
    )

    fun setReprocessingFlow(nr: Boolean?, edge: Boolean?)
}