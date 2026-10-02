package com.letsblog.media.service;

/** 自分自身または自分の子孫を親にしようとした(issue #1493)。階層に循環を作らないため409で拒否する。 */
public class FolderHierarchyCycleException extends RuntimeException {
    public FolderHierarchyCycleException(String message) {
        super(message);
    }
}
