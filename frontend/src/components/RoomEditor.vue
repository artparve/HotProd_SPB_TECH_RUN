<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import RoomPlanImport from './RoomPlanImport.vue'
import { roomLabels, roomSchema, roomTypes, workLabels } from '../domain/types'
import type { Room, RoomType, Work } from '../domain/types'
import { geometry } from '../domain/room'
import { number } from '../domain/format'

const props = defineProps<{ room: Room; showValidation?: boolean }>()
const icons: Record<RoomType, 'bed' | 'bath' | 'kitchen' | 'sun' | 'home'> = {
  living: 'bed',
  bathroom: 'bath',
  kitchen: 'kitchen',
  children: 'home',
  balcony: 'sun',
}
const g = computed(() => geometry(props.room))
const issues = computed(() => {
  const result = roomSchema.safeParse(props.room)
  return result.success ? [] : result.error.issues.map((issue) => issue.message)
})
function setType(type: RoomType) {
  const room = props.room
  room.type = type
  room.humidity = type === 'bathroom' || type === 'kitchen' ? 'high' : 'normal'
  room.heated = type !== 'balcony'
  room.load = type === 'kitchen' ? 'high' : 'normal'
}
function setWall(work: Work) {
  const other = work === 'paint' ? 'wallpaper' : 'paint'
  props.room.works = props.room.works.filter((item) => item !== other)
}
</script>

<template>
  <div class="room-editor">
    <fieldset class="field-group">
      <legend class="field-title">Назначение комнаты</legend>
      <div class="room-type-grid">
        <button
          v-for="type in roomTypes"
          :key="type"
          type="button"
          class="room-type"
          :class="{ selected: room.type === type }"
          :aria-pressed="room.type === type"
          @click="setType(type)"
        >
          <AppIcon :name="icons[type]" :size="24" /><span>{{ roomLabels[type] }}</span
          ><span v-if="room.type === type" class="selection-dot" />
        </button>
      </div>
    </fieldset>

    <RoomPlanImport :room="room" />
    <slot name="inspiration" />
    <div class="section-heading small">
      <h3>Размеры помещения</h3>
      <span class="muted">В метрах, по внутренним стенам</span>
    </div>
    <div class="dimensions-grid">
      <label class="field"
        >Длина
        <div class="input-unit">
          <input
            v-model.number="room.length"
            type="number"
            min="0.1"
            max="100"
            step="0.1"
            required
            inputmode="decimal"
          /><span>м</span>
        </div></label
      >
      <label class="field"
        >Ширина
        <div class="input-unit">
          <input
            v-model.number="room.width"
            type="number"
            min="0.1"
            max="100"
            step="0.1"
            required
            inputmode="decimal"
          /><span>м</span>
        </div></label
      >
      <label class="field"
        >Высота потолка
        <div class="input-unit">
          <input
            v-model.number="room.height"
            type="number"
            min="1"
            max="10"
            step="0.1"
            required
            inputmode="decimal"
          /><span>м</span>
        </div></label
      >
      <label class="field"
        >Окна и двери
        <div class="input-unit">
          <input
            v-model.number="room.openings"
            type="number"
            min="0"
            max="2000"
            step="0.1"
            required
            inputmode="decimal"
          /><span>м²</span>
        </div></label
      >
    </div>
    <div class="geometry-strip">
      <span
        ><AppIcon name="square" :size="16" /> Пол
        <strong>{{ Number.isFinite(g.area) ? number(g.area) : '—' }} м²</strong></span
      ><span
        ><AppIcon name="layers" :size="16" /> Стены
        <strong
          >{{ Number.isFinite(g.walls) && g.walls > 0 ? number(g.walls) : '—' }} м²</strong
        ></span
      ><span class="geometry-hint">Проёмы вычтены из стен</span>
    </div>

    <fieldset class="field-group works-fieldset">
      <legend class="field-title">Что планируете сделать?</legend>
      <div class="works-grid">
        <label
          v-for="(label, work) in workLabels"
          :key="work"
          class="checkbox-card"
          :class="{ checked: room.works.includes(work) }"
          ><input
            v-model="room.works"
            type="checkbox"
            :value="work"
            @change="
              (work === 'paint' || work === 'wallpaper') &&
              room.works.includes(work) &&
              setWall(work)
            "
          /><AppIcon
            :name="
              work === 'floor'
                ? 'grid'
                : work === 'paint'
                  ? 'paint'
                  : work === 'wallpaper'
                    ? 'layers'
                    : 'cable'
            "
            :size="18"
          /><span>{{ label }}</span></label
        >
      </div>
    </fieldset>

    <details class="advanced-settings">
      <summary>
        <AppIcon name="settings" :size="17" /><span>Условия и параметры расчёта</span
        ><AppIcon name="down" :size="15" />
      </summary>
      <div class="advanced-content">
        <div class="dimensions-grid two-col">
          <label class="field"
            >Влажность<select v-model="room.humidity">
              <option value="normal">Обычная</option>
              <option value="high">Повышенная</option>
            </select></label
          ><label class="field"
            >Нагрузка<select v-model="room.load">
              <option value="normal">Обычная</option>
              <option value="high">Повышенная</option>
            </select></label
          >
        </div>
        <label class="inline-check"
          ><input v-model="room.heated" type="checkbox" /> Отапливаемое помещение</label
        >
        <div class="dimensions-grid two-col">
          <label class="field"
            >Запас на потери
            <div class="input-unit">
              <input
                v-model.number="room.reserve"
                type="number"
                min="0"
                max="30"
                step="1"
                required
              /><span>%</span>
            </div></label
          ><label v-if="room.works.includes('paint')" class="field"
            >Количество слоёв<input
              v-model.number="room.layers"
              type="number"
              min="1"
              max="4"
              step="1"
              required
          /></label>
        </div>
        <p class="field-hint">
          Запас покрывает подрезку и потери, упаковки округляются вверх. Для краски норма указана на
          один слой.
        </p>
        <div v-if="room.works.includes('electrical')" class="dimensions-grid two-col">
          <label class="field"
            >Количество розеток<input
              v-model.number="room.sockets"
              type="number"
              min="0"
              max="200"
              step="1"
              required /></label
          ><label class="field"
            >Длина кабельных трасс, м<input
              v-model.number="room.cableLength"
              type="number"
              min="0"
              max="5000"
              step="0.1"
              required
          /></label>
        </div>
      </div>
    </details>
    <div v-if="showValidation && issues.length" class="alert error" role="alert">
      Проверьте размеры и выберите хотя бы один вид работ.
      {{
        issues.includes('Площадь проёмов должна быть меньше площади стен.')
          ? 'Площадь проёмов должна быть меньше площади стен.'
          : ''
      }}
    </div>
  </div>
</template>
