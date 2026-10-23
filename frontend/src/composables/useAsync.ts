import { ref } from 'vue'

export function useAsync() {
  const busy = ref(false)
  const error = ref('')
  async function run<T>(action: () => Promise<T>): Promise<T | undefined> {
    if (busy.value) return
    busy.value = true
    error.value = ''
    try {
      return await action()
    } catch (e) {
      error.value =
        e instanceof Error ? e.message : 'Не удалось выполнить запрос. Попробуйте ещё раз.'
    } finally {
      busy.value = false
    }
  }
  return { busy, error, run }
}
