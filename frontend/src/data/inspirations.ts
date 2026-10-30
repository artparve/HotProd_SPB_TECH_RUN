import type { Project } from '../domain/types'
import type { RoomInspirationFeed, RoomInspirationResult } from '../domain/inspirations'

// Explicit synthetic fixtures, not photographs, ML output or a production catalog.
export const inspirationFeedMock: RoomInspirationFeed = {
  items: [
    {
      id: 'demo-warm-living',
      title: 'Тёплая гостиная',
      description: 'Светлый пол и спокойные стены. Учебная иллюстрация интерьера.',
      thumbnailUrl: '/images/inspirations/warm-living.svg',
      imageUrl: '/images/inspirations/warm-living.svg',
      alt: 'Светлая гостиная с зелёным диваном, деревянным полом и большим окном',
    },
    {
      id: 'demo-blue-living',
      title: 'Синий акцент',
      description: 'Плитка и прохладные оттенки стен. Учебная иллюстрация интерьера.',
      thumbnailUrl: '/images/inspirations/blue-living.svg',
      imageUrl: '/images/inspirations/blue-living.svg',
      alt: 'Комната с синими стенами, светлым диваном и плиточным полом',
    },
    {
      id: 'demo-soft-living',
      title: 'Мягкие оттенки',
      description: 'Обои и светлый ламинат. Учебная иллюстрация интерьера.',
      thumbnailUrl: '/images/inspirations/soft-living.svg',
      imageUrl: '/images/inspirations/soft-living.svg',
      alt: 'Гостиная с розовыми стенами, светлым полом, креслом и растением',
    },
  ],
  nextCursor: null,
}

export function analyzeInspirationMock(imageId: string, source: Project): RoomInspirationResult {
  if (!inspirationFeedMock.items.some((image) => image.id === imageId))
    throw new Error('Изображение больше недоступно. Обновите ленту.')
  const project = structuredClone(source)
  const room = project.rooms[0]!
  const restricted = room.humidity === 'high' || !room.heated || room.load === 'high'
  const wallpaper = imageId === 'demo-soft-living' && !restricted
  room.works = ['floor', wallpaper ? 'wallpaper' : 'paint']
  const tile = imageId === 'demo-blue-living' || restricted
  return {
    imageId,
    project,
    selections: {
      [`${room.id}:covering`]: tile ? 'demo-tile' : 'demo-laminate',
      [`${room.id}:${wallpaper ? 'wallpaper' : 'paint'}`]: wallpaper
        ? 'demo-wallpaper'
        : restricted
          ? 'demo-wet-paint'
          : 'demo-paint',
    },
    warnings: [
      'Демонстрационный подбор по фикстуре, без распознавания изображения и без ML.',
      'Размеры взяты из вашего проекта, а не из изображения. Проверьте их перед расчётом.',
      'Мебель, декор и освещение на иллюстрации не входят в поддерживаемые материалы калькулятора.',
    ],
  }
}
