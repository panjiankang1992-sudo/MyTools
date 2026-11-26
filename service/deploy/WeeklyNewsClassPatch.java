import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

/** 仅给线上任务实例类增加新闻域校验，保留尚未发布的其他领域改动。 */
public final class WeeklyNewsClassPatch {
    private WeeklyNewsClassPatch() { }

    /** 读取已核对的线上类并写入只增加两处校验调用的发布类。 */
    public static void main(String[] args) throws Exception {
        byte[] original = Files.readAllBytes(Path.of(args[0]));
        ClassReader reader = new ClassReader(original);
        String prefix = "com/yuyutian/mytools/task/scheduler/";
        if (!reader.getClassName().equals(prefix + "service/TaskInstanceService")
                || new String(original, java.nio.charset.StandardCharsets.ISO_8859_1).contains("NewsTaskAccess")) {
            throw new IllegalArgumentException("Unexpected baseline");
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] calls = {0, 0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                boolean create = name.equals("create") && descriptor.equals("(L"+prefix+"model/CreateTaskRequest;)L"+prefix+"model/TaskInstanceView;");
                boolean get = name.equals("get") && descriptor.equals("(Ljava/util/UUID;)L"+prefix+"model/TaskInstanceView;");
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public void visitCode() {
                        super.visitCode();
                        if (create) {
                            // 现有任务逻辑前增加新闻创建契约校验。
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, prefix+"service/NewsTaskAccess", "create", "(L"+prefix+"model/CreateTaskRequest;)V", false);
                            calls[0]++;
                        }
                    }
                    @Override public void visitInsn(int opcode) {
                        if (get && opcode == Opcodes.ARETURN) {
                            // 保持原返回对象，在返回前执行同一身份域校验。
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, prefix+"service/NewsTaskAccess", "read", "(L"+prefix+"model/TaskInstanceView;)V", false);
                            calls[1]++;
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        if (calls[0] != 1 || calls[1] != 1) throw new IllegalArgumentException("Unexpected method structure");
        Files.write(Path.of(args[1]), writer.toByteArray());
    }
}
