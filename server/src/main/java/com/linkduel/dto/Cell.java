package com.linkduel.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 棋盘格子(三消玩法:棋盘永远全满,格子只有位置与图案)。
 * id 为 0..63(行 = id / 8,列 = id % 8),图案随交换/下落/补块变化。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Cell {

    private int id;
    private String emoji;
}
