import type { AtomicRegistration } from '../src/auth/atomicRegistration'

/** Test driver for three separate user actions; never a production shortcut. */
export async function completeAtomicSteps(controller: AtomicRegistration, handle: string): Promise<void> {
  if (controller.getSnapshot().phase === 'handle') controller.chooseHandle(handle)
  if (['passkey', 'credential-pending'].includes(controller.getSnapshot().phase)) await controller.createPasskey()
  await controller.save(handle)
}
