package com.aplat.kernel;

/** 依赖的能力缝未装配。启动装配期抛出即失败，避免运行到一半才炸。 */
public class MissingSeamException extends RuntimeException {

    private final Class<?> seam;

    public MissingSeamException(Class<?> seam) {
        super("missing seam: " + seam.getName()
                + " (bind it during assembly, or use ctx.optional(...) for degradable deps)");
        this.seam = seam;
    }

    public Class<?> seam() {
        return seam;
    }
}
