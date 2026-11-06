export const guideEnabled = import.meta.env.VITE_GUIDE_ENABLED !== 'false'
export const splashEnabled = import.meta.env.VITE_SPLASH_ENABLED !== 'false'

export const startupReady = new Promise<void>((resolve) => {
  const finish = () => {
    document.getElementById('startup-screen')?.remove()
    resolve()
  }
  if (!splashEnabled || !document.getElementById('startup-screen')) finish()
  else window.setTimeout(finish, Math.max(0, 1000 - performance.now()))
})
