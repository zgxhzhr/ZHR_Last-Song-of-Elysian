package com.ovo.lastsongofelysian.client.mesh;

/**
 * 三角网格渲染的调试模式。按 F9 循环切换，用来把「数据问题」和「渲染管线问题」分开。
 *
 * <p>排查思路：
 * <ul>
 *   <li>{@link #NORMAL} 正常贴图渲染</li>
 *   <li>{@link #WHITE} 全白。如果这时仍然有噪点，说明不是贴图/UV 的问题，而是几何或光照</li>
 *   <li>{@link #WIREFRAME} 线框。能看清三角形是怎么连的、有没有跨面、有没有碎裂</li>
 *   <li>{@link #NORMALS} 法线。从每个顶点沿法线画短线，检查法线方向是否一致</li>
 * </ul>
 */
public final class MeshDebug {

    public enum Mode {
        NORMAL("正常"),
        WHITE("全白"),
        WIREFRAME("线框"),
        NORMALS("法线");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private static Mode current = Mode.NORMAL;

    private MeshDebug() {
    }

    public static Mode mode() {
        return current;
    }

    public static Mode cycle() {
        Mode[] all = Mode.values();
        current = all[(current.ordinal() + 1) % all.length];
        return current;
    }

    public static void set(Mode m) {
        current = m;
    }
}
