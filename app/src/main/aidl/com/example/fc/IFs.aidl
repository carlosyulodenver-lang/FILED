package com.example.fc;

interface IFs {
    void destroy() = 16777114; // required by Shizuku
    boolean isDir(String l) = 1;
    String name(String l) = 2;
    String[] list(String d) = 3;
    String child(String d, String n) = 4;   // null if absent
    String mkdir(String d, String n) = 5;
    String copy(String s, String d, String n) = 6;   // file or folder, recursive
    String move(String l, String d, String n) = 7;
    void delete(String l) = 8;               // recursive, no-op if absent
    long[] stat(String l) = 10;              // {size, lastModified}; size = -1 for folders
    long free(String d) = 11;                // free bytes at d, -1 if unknown
    String[] ls(String d) = 9;               // "d|name" for folders, "f|name" for files; throws if unreadable
}
