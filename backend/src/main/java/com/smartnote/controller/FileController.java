package com.smartnote.controller;

import com.smartnote.dto.VditorUploadResponse;
import com.smartnote.service.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;

    @Autowired
    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    public ResponseEntity<VditorUploadResponse> uploadFiles(@RequestParam("file[]") MultipartFile[] files) {
        List<String> errFiles = new ArrayList<>();
        Map<String, String> succMap = new HashMap<>();

        for (MultipartFile file : files) {
            String originalFileName = file.getOriginalFilename();
            try {
                String fileName = fileService.storeFile(file);

                // Build the file download URI
                String fileDownloadUri = ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/uploads/")
                        .path(fileName)
                        .toUriString();

                succMap.put(originalFileName, fileDownloadUri);
            } catch (Exception e) {
                errFiles.add(originalFileName);
            }
        }

        VditorUploadResponse.Data data = new VditorUploadResponse.Data(errFiles, succMap);
        VditorUploadResponse response;
        if (errFiles.isEmpty()) {
            response = new VditorUploadResponse("Upload successful", 0, data);
        } else {
            response = new VditorUploadResponse("Some files failed to upload", 1, data);
        }

        return ResponseEntity.ok(response);
    }
}
