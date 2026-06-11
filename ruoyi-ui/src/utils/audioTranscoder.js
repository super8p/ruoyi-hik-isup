/**
 * G.711A (A-law) 压缩算法实现 (将 16-bit 线性 PCM 转换成 8-bit A-law)
 */
export function linearToAlaw(sample) {
  let sign = (sample >> 8) & 0x80;
  if (sample < 0) {
    sample = -sample;
    sign = 0x80;
  }
  // 裁剪最大值
  if (sample > 32767) sample = 32767;

  let exponent = 7;
  for (let expMask = 0x4000; (sample & expMask) === 0 && exponent > 0; exponent--) {
    sample <<= 1;
  }

  let mantissa = (sample >> (exponent === 0 ? 4 : exponent + 3)) & 0x0f;
  let alaw = ((exponent << 4) | mantissa) ^ 0x55;
  return sign ? (alaw & 0x7f) : (alaw | 0x80);
}

/**
 * 降采样函数：将 inputSampleRate (如 48000Hz) 转换为 outputSampleRate (如 8000Hz)
 * 并将 PCM Float32 编码为 G.711A (A-law)
 */
export function resampleAndEncodeToAlaw(inputBuffer, inputSampleRate, outputSampleRate = 8000) {
  const compressionRatio = inputSampleRate / outputSampleRate;
  const outputLength = Math.floor(inputBuffer.length / compressionRatio);
  const outputBuffer = new Uint8Array(outputLength);

  for (let i = 0; i < outputLength; i++) {
    const sourceIndex = Math.floor(i * compressionRatio);
    // 提取 16-bit 振幅
    const sample = Math.max(-1, Math.min(1, inputBuffer[sourceIndex])) * 0x7FFF;
    outputBuffer[i] = linearToAlaw(Math.floor(sample));
  }
  return outputBuffer;
}
