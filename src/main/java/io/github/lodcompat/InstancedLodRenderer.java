package io.github.lodcompat;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.Camera;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

@Environment(EnvType.CLIENT)
public class InstancedLodRenderer {
    private static final int QUAD_VERTEX_COUNT = 6; // 2 triangles per quad
    private static final int INSTANCE_BUFFER_SIZE = 10000; // max quad instances per batch

    private int shaderProgram;
    private int VAO, quadVBO, instanceVBO;
    private int instanceCount = 0;
    private FloatBuffer instanceBuffer;
    private double playerX, playerY, playerZ;

    public void initialize() {
        RenderSystem.assertOnRenderThread();

        // Compile shaders
        this.shaderProgram = createShaderProgram(
            getVertexShaderSource(),
            getFragmentShaderSource()
        );

        if (shaderProgram == 0) {
            throw new RuntimeException("Failed to create shader program");
        }

        // Setup VAO & VBO untuk quad vertices (static)
        this.VAO = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(VAO);

        // Quad vertices (screen-space, nanti dipindahin ke world-space via instance matrix)
        float[] quadVertices = {
            0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 0.0f,
            1.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f
        };

        this.quadVBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quadVertices, GL15.GL_STATIC_DRAW);

        // Vertex attrib: position (layout 0)
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);

        // Instance VBO (buat data per-instance: pos_x, pos_y, pos_z, width, height, color_r, color_g, color_b)
        this.instanceVBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, INSTANCE_BUFFER_SIZE * 8 * Float.BYTES, GL15.GL_DYNAMIC_DRAW);

        // Instance attribs (layout 1-8)
        // layout 1: instance position xyz (offset 0)
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 8 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(1);
        GL33.glVertexAttribDivisor(1, 1);

        // layout 2: width, height (offset 3 floats)
        GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, 8 * Float.BYTES, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);
        GL33.glVertexAttribDivisor(2, 1);

        // layout 3: color rgb (offset 5 floats)
        GL20.glVertexAttribPointer(3, 3, GL11.GL_FLOAT, false, 8 * Float.BYTES, 5 * Float.BYTES);
        GL20.glEnableVertexAttribArray(3);
        GL33.glVertexAttribDivisor(3, 1);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);

        this.instanceBuffer = MemoryUtil.memAllocFloat(INSTANCE_BUFFER_SIZE * 8);

        LodCompatClient.LOGGER.info("InstancedLodRenderer initialized: VAO={}, quadVBO={}, instanceVBO={}", VAO, quadVBO, instanceVBO);
    }

    public void updatePlayerPosition(double x, double y, double z) {
        this.playerX = x;
        this.playerY = y;
        this.playerZ = z;
    }

    public void render(Camera camera) {
        RenderSystem.assertOnRenderThread();

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_DEPTH_TEST);

        GL20.glUseProgram(shaderProgram);

        // Setup uniforms (projection, view matrices)
        // TODO: inject actual matrices dari Minecraft render context
        
        GL30.glBindVertexArray(VAO);

        if (instanceCount > 0) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVBO);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, instanceBuffer);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            // Draw with instancing (glDrawArrays karena quad kita raw vertices, bukan indexed)
            GL31.glDrawArraysInstanced(
                GL11.GL_TRIANGLES,
                0,
                QUAD_VERTEX_COUNT,
                instanceCount
            );
        }

        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);

        GL11.glDisable(GL11.GL_BLEND);
    }

    public void addQuadInstance(float x, float y, float z, float width, float height, float r, float g, float b) {
        if (instanceCount >= INSTANCE_BUFFER_SIZE) {
            LodCompatClient.LOGGER.warn("Instance buffer full, skipping quad");
            return;
        }

        int offset = instanceCount * 8;
        instanceBuffer.put(offset, x);
        instanceBuffer.put(offset + 1, y);
        instanceBuffer.put(offset + 2, z);
        instanceBuffer.put(offset + 3, width);
        instanceBuffer.put(offset + 4, height);
        instanceBuffer.put(offset + 5, r);
        instanceBuffer.put(offset + 6, g);
        instanceBuffer.put(offset + 7, b);

        instanceCount++;
    }

    public void clearInstances() {
        instanceCount = 0;
        instanceBuffer.clear();
    }

    public void cleanup() {
        RenderSystem.assertOnRenderThread();
        
        if (VAO != 0) GL30.glDeleteVertexArrays(VAO);
        if (quadVBO != 0) GL15.glDeleteBuffers(quadVBO);
        if (instanceVBO != 0) GL15.glDeleteBuffers(instanceVBO);
        if (shaderProgram != 0) GL20.glDeleteProgram(shaderProgram);
        if (instanceBuffer != null) MemoryUtil.memFree(instanceBuffer);
    }

    private int createShaderProgram(String vertexSource, String fragmentSource) {
        int vertex = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vertex, vertexSource);
        GL20.glCompileShader(vertex);

        if (GL20.glGetShaderi(vertex, GL20.GL_COMPILE_STATUS) == 0) {
            LodCompatClient.LOGGER.error("Vertex shader compilation failed: {}", GL20.glGetShaderInfoLog(vertex));
            return 0;
        }

        int fragment = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(fragment, fragmentSource);
        GL20.glCompileShader(fragment);

        if (GL20.glGetShaderi(fragment, GL20.GL_COMPILE_STATUS) == 0) {
            LodCompatClient.LOGGER.error("Fragment shader compilation failed: {}", GL20.glGetShaderInfoLog(fragment));
            return 0;
        }

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        GL20.glLinkProgram(program);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            LodCompatClient.LOGGER.error("Shader program linking failed: {}", GL20.glGetProgramInfoLog(program));
            return 0;
        }

        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);

        return program;
    }

    private String getVertexShaderSource() {
        return "#version 330 core\n" +
            "layout (location = 0) in vec3 position;\n" +
            "layout (location = 1) in vec3 instancePos;\n" +
            "layout (location = 2) in vec2 instanceSize;\n" +
            "layout (location = 3) in vec3 instanceColor;\n" +
            "out vec3 fragColor;\n" +
            "uniform mat4 projection;\n" +
            "uniform mat4 view;\n" +
            "void main() {\n" +
            "  vec3 worldPos = instancePos + vec3(position.x * instanceSize.x, position.y * instanceSize.y, position.z);\n" +
            "  gl_Position = projection * view * vec4(worldPos, 1.0);\n" +
            "  fragColor = instanceColor;\n" +
            "}";
    }

    private String getFragmentShaderSource() {
        return "#version 330 core\n" +
            "in vec3 fragColor;\n" +
            "out vec4 FragColor;\n" +
            "void main() {\n" +
            "  FragColor = vec4(fragColor, 0.8);\n" +
            "}";
    }
  }
