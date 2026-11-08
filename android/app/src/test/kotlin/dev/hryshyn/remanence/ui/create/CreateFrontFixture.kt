package dev.hryshyn.remanence.ui.create

/**
 * The postcard front is photographed last: "attach" opens the camera and the
 * delivered photo attaches the composed postcard. Tests that publish go
 * through this one path instead of capturing the front up front.
 */
internal object CreateFrontFixture {
    fun attach(vm: CreateViewModel, bytes: ByteArray = "f".toByteArray()) {
        vm.attach()
        if (vm.step.value != CreateViewModel.Step.FRONT) return
        vm.frontAttempt.onPermissionResult(true, false)
        vm.frontAttempt.onPreviewBound()
        check(vm.beginFrontCapture()) { "front capture did not begin" }
        vm.deliverFrontJpeg(bytes)
    }
}
