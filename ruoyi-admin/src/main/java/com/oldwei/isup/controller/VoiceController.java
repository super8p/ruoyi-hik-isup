package com.oldwei.isup.controller;

import com.oldwei.isup.config.HikPlatformProperties;
import com.oldwei.isup.model.Device;
import com.oldwei.isup.model.R;
import com.oldwei.isup.model.tts.DataItem;
import com.oldwei.isup.service.DeviceCacheService;
import com.oldwei.isup.service.IMediaStreamService;
import io.github.whitemagic2014.tts.TTS;
import io.github.whitemagic2014.tts.TTSVoice;
import io.github.whitemagic2014.tts.bean.Voice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 语音对讲控制接口
 */
@Slf4j
@RestController
@RequestMapping("/api/devices/{deviceId}/voice")
@RequiredArgsConstructor
public class VoiceController {

    private final IMediaStreamService mediaStreamService;
    private final DeviceCacheService deviceCacheService;
    private final HikPlatformProperties hikPlatformProperties;

    private static final String UPLOAD_DIR = "container/upload/audio/";

    /**
     * TTS语音播报
     */
    @PostMapping("/tts")
    public R<Object> sendTtsVoice(
            @PathVariable String deviceId,
            @RequestBody DataItem dataItem) {

        // 增加一个判断，如果文件夹不存在则创建文件夹
        try {
            Path uploadDirPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadDirPath)) {
                Files.createDirectories(uploadDirPath);
            }
        } catch (IOException e) {
            log.error("创建上传目录失败: {}", e.getMessage());
            return R.fail("服务器内部错误，无法创建上传目录");
        }
        // 生成唯一文件名
        String filename = UUID.randomUUID().toString();
        String pcmFilename = "tts_" + filename + "_8k.pcm";
        Path pcmTargetPath = Paths.get(UPLOAD_DIR, pcmFilename);

        // 查询设备
        Optional<Device> deviceOpt = deviceCacheService.getByDeviceId(deviceId);
        if (deviceOpt.isEmpty()) {
            return R.fail("设备ID不存在: " + deviceId);
        }

        Device device = deviceOpt.get();
        Integer loginId = device.getLoginId();

        // 临时生成路径
        Path mp3TargetPath = null;

        try {
            // 获取 Edge TTS 语音
            String targetVoiceName = "zh-CN-YunjianNeural";
            List<Voice> voices = TTSVoice.provides();
            Voice voice = voices.stream()
                    .filter(v -> v.getShortName().equals(targetVoiceName))
                    .findFirst()
                    .orElse(null);

            if (voice == null && !voices.isEmpty()) {
                // 如果找不到目标语音，选择任意 zh-CN Neural 语音，或者第一个语音
                voice = voices.stream()
                        .filter(v -> v.getShortName().startsWith("zh-CN"))
                        .findFirst()
                        .orElse(voices.get(0));
            }

            if (voice == null) {
                throw new RuntimeException("未能获取任何有效的 TTS 语音配置");
            }

            // 创建并执行 TTS 转换任务
            TTS tts = new TTS(voice, dataItem.getText())
                    .storage(UPLOAD_DIR)
                    .fileName("tts_" + filename)
                    .formatMp3()
                    .overwrite(true)
                    .isRateLimited(true);

            // trans() 执行后保存文件，并返回生成的文件名（可能是 "tts_xxx.mp3"）
            String generatedFileName = tts.trans();
            if (generatedFileName == null || generatedFileName.isEmpty()) {
                throw new RuntimeException("TTS 语音生成返回的文件名为空");
            }

            mp3TargetPath = Paths.get(generatedFileName);
            if (!Files.exists(mp3TargetPath)) {
                mp3TargetPath = Paths.get(UPLOAD_DIR, generatedFileName);
            }

            // 检查MP3文件是否存在且有内容
            if (!Files.exists(mp3TargetPath) || Files.size(mp3TargetPath) == 0) {
                throw new IllegalArgumentException("生成的音频文件为空或不存在: " + mp3TargetPath.toString());
            }

            // 使用FFmpeg将MP3转换为8K μ-law PCM
            String ffmpegCmd = hikPlatformProperties.getFfmpegPath() != null && !hikPlatformProperties.getFfmpegPath().isEmpty()
                    ? hikPlatformProperties.getFfmpegPath() : "ffmpeg";
            ProcessBuilder processBuilder = new ProcessBuilder(
                    ffmpegCmd,
                    "-i", mp3TargetPath.toAbsolutePath().toString(),
                    "-f", "mulaw",
                    "-ac", "1",
                    "-ar", "8000",
                    "-acodec", "pcm_mulaw",
                    "-y",
                    pcmTargetPath.toAbsolutePath().toString()
            );

            Process process = processBuilder.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                StringBuilder errorOutput = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorOutput.append(line).append("\n");
                    }
                }
                throw new RuntimeException("音频转码失败,FFmpeg退出码: " + exitCode +
                        ", 错误信息: " + errorOutput.toString());
            }

            // 检查转换后的PCM文件是否存在
            if (!Files.exists(pcmTargetPath)) {
                throw new RuntimeException("转换后的PCM文件不存在: " + pcmTargetPath.toString());
            }

            // 调用转录服务
            mediaStreamService.voiceTrans(loginId, pcmTargetPath.toAbsolutePath().toString());

            return R.ok(pcmTargetPath.toAbsolutePath().toString());
        } catch (Exception error) {
            log.error("TTS语音播报失败: {}", error.getMessage(), error);
            // 发生错误时清理临时文件
            try {
                if (mp3TargetPath != null && Files.exists(mp3TargetPath)) {
                    Files.delete(mp3TargetPath);
                }
                if (Files.exists(pcmTargetPath)) {
                    Files.delete(pcmTargetPath);
                }
            } catch (Exception e) {
                log.error("清理临时文件失败: {}", e.getMessage());
            }
            return R.fail("TTS语音播报失败: " + error.getMessage());
        } finally {
            // 清理生成的临时 mp3 文件以防堆积
            try {
                if (mp3TargetPath != null && Files.exists(mp3TargetPath)) {
                    Files.delete(mp3TargetPath);
                }
            } catch (Exception e) {
                log.error("清理临时 MP3 文件失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 上传音频对讲
     */
    @PostMapping("/upload")
    public R<Object> uploadVoice(
            @PathVariable String deviceId,
            @RequestPart("file") MultipartFile file) {

        try {
            Path uploadDirPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadDirPath)) {
                Files.createDirectories(uploadDirPath);
            }
        } catch (IOException e) {
            log.error("创建上传目录失败: {}", e.getMessage());
            return R.fail("服务器内部错误，无法创建上传目录");
        }
        // 生成唯一文件名
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            originalFilename = "";
        }
        String suffix = originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : "";
        String filename = UUID.randomUUID().toString() + suffix;
        Path targetPath = Paths.get(UPLOAD_DIR, filename);

        // 查询设备
        Optional<Device> deviceOpt = deviceCacheService.getByDeviceId(deviceId);
        if (deviceOpt.isEmpty()) {
            return R.fail("设备ID不存在: " + deviceId);
        }

        Device device = deviceOpt.get();
        Integer loginId = device.getLoginId();

        // 保存文件并转码,然后调用转录服务
        try {
            file.transferTo(targetPath.toFile());

            // 检查文件是否存在且有内容
            if (!Files.exists(targetPath) || Files.size(targetPath) <= 44) {
                throw new IllegalArgumentException("无效的音频文件,文件大小异常");
            }

            // 生成转码后的文件路径
            String originalFileName = targetPath.getFileName().toString();
            String baseName = originalFileName.contains(".")
                    ? originalFileName.substring(0, originalFileName.lastIndexOf("."))
                    : originalFileName;
            String g711uFilename = baseName + "_g711u.pcm";
            Path g711uTargetPath = Paths.get(UPLOAD_DIR, g711uFilename);

            // 使用FFmpeg将原始文件转换为G.711 μ-law
            String ffmpegCmd = hikPlatformProperties.getFfmpegPath() != null && !hikPlatformProperties.getFfmpegPath().isEmpty()
                    ? hikPlatformProperties.getFfmpegPath() : "ffmpeg";
            ProcessBuilder processBuilder = new ProcessBuilder(
                    ffmpegCmd,
                    "-i", targetPath.toAbsolutePath().toString(),
                    "-f", "mulaw",
                    "-ac", "1",
                    "-ar", "8000",
                    "-acodec", "pcm_mulaw",
                    "-y",
                    g711uTargetPath.toAbsolutePath().toString()
            );

            Process process = processBuilder.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                StringBuilder errorOutput = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorOutput.append(line).append("\n");
                    }
                }
                throw new RuntimeException("音频转码失败,FFmpeg退出码: " + exitCode +
                        ", 错误信息: " + errorOutput.toString());
            }

            // 检查转码后的文件是否存在
            if (!Files.exists(g711uTargetPath)) {
                throw new RuntimeException("转码后的文件不存在: " + g711uTargetPath.toString());
            }

            // 获取转码后的完整文件路径
            String g711uFileFullPath = g711uTargetPath.toAbsolutePath().toString();

            // 调用转录服务,传入转码后的文件路径
            mediaStreamService.voiceTrans(loginId, g711uFileFullPath);

            return R.ok(g711uFileFullPath);
        } catch (Exception error) {
            log.error("上传音频对讲失败: {}", error.getMessage());
            // 发生错误时清理临时文件
            try {
                String originalFileName = targetPath.getFileName().toString();
                String baseName = originalFileName.contains(".")
                        ? originalFileName.substring(0, originalFileName.lastIndexOf("."))
                        : originalFileName;
                String g711uFilename = baseName + "_g711u.pcm";
                Path g711uTargetPath = Paths.get(UPLOAD_DIR, g711uFilename);

                if (Files.exists(targetPath)) {
                    Files.delete(targetPath);
                }
                if (Files.exists(g711uTargetPath)) {
                    Files.delete(g711uTargetPath);
                }
            } catch (Exception e) {
                log.error("清理临时文件失败: {}", e.getMessage());
            }
            return R.fail("上传音频对讲失败: " + error.getMessage());
        }
    }
}
