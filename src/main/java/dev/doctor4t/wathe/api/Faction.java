package dev.doctor4t.wathe.api;

/**
 * 回放与结算里使用的“阵营语义”。
 *
 * <p>这里和职业颜色是两套概念：</p>
 * <p>1. 阵营颜色用于玩家名字本体着色，表达“这个职业属于哪一侧”；</p>
 * <p>2. 职业颜色用于括号里的职业名着色，表达“这个具体职业自己的主题色”。</p>
 *
 * <p>这样后续扩展职业模组就可以实现你需要的效果：
 * 名字按杀手 / 中立 / 义警 / 平民阵营上色，
 * 职业名再按各自职业 RGB 单独显示。</p>
 */
public enum Faction {
    CIVILIAN(0x36E51B),
    VIGILANTE(0x1B8AE5),
    KILLER(0xC13838),
    NEUTRAL(0xE0B637);

    private final int displayColor;

    Faction(int displayColor) {
        this.displayColor = displayColor;
    }

    public int displayColor() {
        return this.displayColor;
    }
}
