// 与后端 Match3Engine/结算逻辑保持一致的常量

/** 6 种水果图案(与后端 Match3Engine.TILE_EMOJIS 一致) */
export const TILE_EMOJIS = ['🍎', '🍌', '🍇', '🍓', '🍊', '🍉']

export const BOARD_SIZE = 8

/** 对局结束原因 → 中文文案 */
export const REASON_LABEL: Record<string, string> = {
  timeout: '时间到',
  forfeit: '对手离线判负',
  join_timeout: '超时未进入,对局取消',
}

/** 结算状态 → 中文文案 */
export const STATUS_LABEL: Record<string, string> = {
  finished: '完赛',
  forfeit: '判负',
  cancelled: '已取消',
}

/** 后端错误码 → 前端提示(权威校验失败时的友好提示) */
export const ERROR_MESSAGE: Record<number, string> = {
  40410: '当前没有进行中的对局',
  40420: '对局不存在或已结束',
  40900: '已在匹配队列中',
  40901: '已匹配成功,无法取消',
  40902: '对局已结束',
  40903: '已在对局中',
  42200: '只能交换相邻的两个格子',
  42202: '不能选择同一个格子',
  42204: '该交换无法形成三连消除',
  42205: '对局尚未开始',
}
