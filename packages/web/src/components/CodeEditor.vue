<template>
  <div ref="host" class="cm-host"></div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { basicSetup } from 'codemirror'
import { Compartment, EditorState } from '@codemirror/state'
import { EditorView } from '@codemirror/view'
import { json } from '@codemirror/lang-json'

const props = withDefaults(
  defineProps<{
    modelValue: string
    readonly?: boolean
    jsonMode?: boolean
  }>(),
  { readonly: false, jsonMode: false },
)

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const host = ref<HTMLElement | null>(null)
let view: EditorView | null = null
const readOnlyComp = new Compartment()
const langComp = new Compartment()

const theme = EditorView.theme({
  '&': { fontSize: '12.5px', height: '100%', backgroundColor: '#ffffff' },
  '.cm-scroller': { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace', lineHeight: '1.6' },
  '.cm-content': { padding: '8px 0' },
  '&.cm-focused': { outline: 'none' },
  '.cm-gutters': { backgroundColor: '#fbfcfd', color: '#94a3b8', border: 'none', borderRight: '1px solid #eef1f4' },
})

onMounted(() => {
  if (!host.value) return
  view = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        basicSetup,
        theme,
        readOnlyComp.of(EditorState.readOnly.of(props.readonly)),
        langComp.of(props.jsonMode ? json() : []),
        EditorView.updateListener.of((u) => {
          if (u.docChanged) emit('update:modelValue', u.state.doc.toString())
        }),
      ],
    }),
  })
})

watch(
  () => props.modelValue,
  (v) => {
    if (view && v !== view.state.doc.toString()) {
      view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: v } })
    }
  },
)

watch(
  () => props.readonly,
  (v) => {
    view?.dispatch({ effects: readOnlyComp.reconfigure(EditorState.readOnly.of(v)) })
  },
)

watch(
  () => props.jsonMode,
  (v) => {
    view?.dispatch({ effects: langComp.reconfigure(v ? json() : []) })
  },
)

onBeforeUnmount(() => {
  view?.destroy()
  view = null
})
</script>
