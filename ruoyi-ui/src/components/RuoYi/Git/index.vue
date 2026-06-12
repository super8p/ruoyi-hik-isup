<template>
  <div>
    <svg-icon icon-class="github" @click="goto" />
  </div>
</template>

<script setup>
const url = ref('https://github.com/super8p/ruoyi-hik-isup');

function goto() {
  window.open(url.value)
}
</script>
