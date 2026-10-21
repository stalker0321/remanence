import { DeploymentConfigurationError } from './config'

/** Compatibility declaration of our static-only worker, not auth attestation.
 * Older/unrelated controllers fail closed, without forced activation or cleanup. */
export async function verifyDeploymentWorker(base: string, origin: string, controller: ServiceWorker | null): Promise<void> {
  if (!controller) return
  if (controller.scriptURL !== new URL(`${base}sw.js`, origin).href || controller.state !== 'activated') {
    throw new DeploymentConfigurationError('DEPLOYMENT_WORKER_UNVERIFIED')
  }
  await new Promise<void>((resolve, reject) => {
    const channel = new MessageChannel()
    const finish = (valid: boolean) => {
      clearTimeout(timer); channel.port1.close(); channel.port2.close()
      if (valid) resolve(); else reject(new DeploymentConfigurationError('DEPLOYMENT_WORKER_UNVERIFIED'))
    }
    const timer = setTimeout(() => finish(false), 1000)
    channel.port1.onmessage = event => {
      const value = event.data
      finish(value?.type === 'REMANENCE_STATIC_POLICY_V1' && value.base === base
        && value.runtimeCache === false && value.configNetworkOnly === true)
    }
    try { controller.postMessage({ type: 'REMANENCE_STATIC_POLICY_V1' }, [channel.port2]) }
    catch { finish(false) }
  })
}
