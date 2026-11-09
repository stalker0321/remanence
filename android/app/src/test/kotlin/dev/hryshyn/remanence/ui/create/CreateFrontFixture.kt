package dev.hryshyn.remanence.ui.create

/**
 * The postcard front is photographed last: "attach" opens the camera and the
 * delivered photo attaches the composed postcard. Tests that publish go
 * through this one path instead of capturing the front up front.
 */
internal object CreateFrontFixture {
    /** Content → layout → attach: the camera is the last screen of Create. */
    fun reachCamera(vm: CreateViewModel) {
        vm.onPhotosPicked(List(3) { "content://camera/$it" })
        try { CollageFreezeFixture.freezeFirstCandidate(vm) }
        catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            error("no chooser input: preview=${vm.generatorPreview.value} step=${vm.step.value} photos=${vm.photoSelection.selectedIds}")
        }
        check(vm.goToCompose()) { "composition refused: ${vm.flowError.value}" }
        vm.attach()
        check(vm.step.value == CreateViewModel.Step.FRONT) { "attach did not open the camera: ${vm.flowError.value}" }
    }

    fun attach(vm: CreateViewModel, bytes: ByteArray = "f".toByteArray()) {
        vm.attach()
        if (vm.step.value != CreateViewModel.Step.FRONT) return
        vm.frontAttempt.onPermissionResult(true, false)
        vm.frontAttempt.onPreviewBound()
        check(vm.beginFrontCapture()) { "front capture did not begin" }
        vm.deliverFrontJpeg(bytes)
    }
}
