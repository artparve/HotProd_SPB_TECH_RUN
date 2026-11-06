// Model text is untrusted: parse only complete JSON (optionally fenced), never evaluate it.
export function explanationJson(text: string): unknown | undefined {
  const value = text
    .trim()
    .replace(/^```(?:json)?\s*\n?([\s\S]*?)\n?```$/i, '$1')
    .trim()
  if (value.length > 100000 || !/^[{[]/.test(value)) return undefined
  try {
    const parsed: unknown = JSON.parse(value)
    return parsed !== null && typeof parsed === 'object' ? parsed : undefined
  } catch {
    return undefined
  }
}

const labels: Record<string, string> = {
  estimate: 'Смета',
  id: 'Идентификатор',
  total: 'Итого',
  materials: 'Материалы',
  labor: 'Работы',
  lines: 'Позиции',
  product: 'Товар',
  name: 'Название',
  quantity: 'Количество',
  need: 'Требуется',
  purchased: 'Куплено',
  remainder: 'Остаток',
  explanation: 'Пояснение',
  warnings: 'Предупреждения',
  recommendations: 'Рекомендации',
  description: 'Описание',
  unit: 'Единица измерения',
  price: 'Цена',
  packages: 'Упаковки',
  formula: 'Формула',
  reserve: 'Запас',
  steps: 'Шаги',
}
export function explanationLabel(key: string): string {
  return Object.hasOwn(labels, key)
    ? labels[key]!
    : key.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/[_-]/g, ' ')
}
