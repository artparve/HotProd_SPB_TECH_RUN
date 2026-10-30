import type { PreparedInspiration } from '../domain/inspirations'

// One-shot handoff across routes; no image blobs or model output in the URL/localStorage.
let pending: PreparedInspiration | null = null
export function stageInspiration(value: PreparedInspiration) {
  pending = value
}
export function takeInspiration() {
  const value = pending
  pending = null
  return value
}
