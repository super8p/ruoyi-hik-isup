package com.oldwei.isup.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;

@Slf4j
@RestController
public class PicController {

    @GetMapping("/pic")
    public ResponseEntity<byte[]> getPic(HttpServletRequest request) {
        String queryString = request.getQueryString();
        if (queryString == null || queryString.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // 提取文件名，例如 "/pic?17B783EAF64BE158B9329302A633D736" 的 queryString 应该是 "17B783EAF64BE158B9329302A633D736"
        String fileName = queryString;
        if (queryString.contains("=")) {
            // 支持类似 ?file=filename 的传参方式
            String[] parts = queryString.split("=");
            if (parts.length > 1) {
                fileName = parts[1];
            } else {
                fileName = parts[0];
            }
        }

        File file = new File(System.getProperty("user.dir") + "/container/ISUPPicServer/" + fileName);
        if (!file.exists()) {
            // 如果不存在，尝试加上常见后缀 jpg 查找
            File fileJpg = new File(file.getAbsolutePath() + ".jpg");
            if (fileJpg.exists()) {
                file = fileJpg;
            } else {
                log.warn("图片文件不存在: {}", file.getAbsolutePath());
                return ResponseEntity.notFound().build();
            }
        }

        try {
            byte[] imageBytes = Files.readAllBytes(file.toPath());
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(imageBytes);
        } catch (Exception e) {
            log.error("读取图片失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
