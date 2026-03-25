package com.smartnote.dto;

import java.util.List;
import java.util.Map;

public class VditorUploadResponse {
    private String msg;
    private int code;
    private Data data;

    public VditorUploadResponse() {
    }

    public VditorUploadResponse(String msg, int code, Data data) {
        this.msg = msg;
        this.code = code;
        this.data = data;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }

    public static class Data {
        private List<String> errFiles;
        private Map<String, String> succMap;

        public Data() {
        }

        public Data(List<String> errFiles, Map<String, String> succMap) {
            this.errFiles = errFiles;
            this.succMap = succMap;
        }

        public List<String> getErrFiles() {
            return errFiles;
        }

        public void setErrFiles(List<String> errFiles) {
            this.errFiles = errFiles;
        }

        public Map<String, String> getSuccMap() {
            return succMap;
        }

        public void setSuccMap(Map<String, String> succMap) {
            this.succMap = succMap;
        }
    }
}
