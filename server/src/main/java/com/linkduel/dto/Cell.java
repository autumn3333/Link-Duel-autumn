package com.linkduel.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 棋盘格子。id 为 0..63(行 = id / 8,列 = id % 8)。
 * eliminated=true 表示已被消除,可视为"空位/可穿过"。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Cell {

    private int id;
    private String emoji;
    private boolean eliminated;
}
