package kr.ac.jbnu.se.tetris.ui;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import kr.ac.jbnu.se.tetris.core.SplitMix64;
import kr.ac.jbnu.se.tetris.sand.Grain;
import kr.ac.jbnu.se.tetris.sand.SandColor;
import kr.ac.jbnu.se.tetris.sand.SandRules;

/**
 * 모래 화면의 색 표: 알갱이의 그리기 번호({@link Grain#look}) → ARGB. 설정(색약 색상, 블록 무늬)마다 한 번 만들어 두고 다시
 * 쓴다({@link #of}). 알갱이마다 객체를 만들지 않도록 그리기는 표에서 int 하나를 꺼내기만 한다.
 * <ul>
 * <li>{@link #table(double)}: 반짝이는(곧 사라질) 알갱이를 흰색 쪽으로 섞은 표. 섞는 정도는 {@value #PULSE_STEPS} 단계로
 * 나눠 미리 만든다. 반짝이지 않는 알갱이는 어느 표에서나 제 색이다. 지우기 한 번의 반짝임(SPEC V14)은 그리는 순간의 섞는
 * 정도로 표를 고른다.</li>
 * <li>{@link #grey(int)}: 판이 끝날 때 아래에서 위로 바뀌는 회색(명암은 그대로).</li>
 * <li>{@link #tile(SandColor)}: 홀드·미리보기 블록의 한 칸(8 × 8 알갱이 그림).</li>
 * </ul>
 */
final class SandColors {

	/** 흰색 섞기 단계 수: 표는 0/32, 1/32, …, 32/32 의 33 개. */
	static final int PULSE_STEPS = 32;
	/** 판이 끝날 때의 회색 (대전 화면의 K.O. 회색, SPEC V9). */
	static final Color KO_GREY = new Color(0x5A6178);

	private static final int G = SandRules.GRAIN;
	/** 미리보기·홀드 블록의 알갱이 명암 (칸 안 64 자리, 판마다 같다). */
	private static final int[] TILE_SHADES = tileShades();
	private static final Map<ColorPalettes.Look, SandColors> CACHE = new ConcurrentHashMap<>();

	final ColorPalettes.Look look;
	private final int[][] pulse = new int[PULSE_STEPS + 1][Grain.LOOKS];
	private final int[] grey = new int[Grain.LOOKS];
	private final BufferedImage[] tiles = new BufferedImage[SandRules.COLORS + 1];

	private SandColors(ColorPalettes.Look look) {
		this.look = look;
		for (int i = 1; i < Grain.LOOKS; i++) {
			SandColor color = Grain.lookColor(i);
			if (color == null) {
				continue;
			}
			// 반짝이는 알갱이도 바탕은 제 색(반짝이지 않는 같은 알갱이)이다. 흰색은 표마다 섞는다.
			Color base = look.grain(color.code(), Grain.lookShade(i), false, Grain.lookPattern(i));
			for (int k = 0; k <= PULSE_STEPS; k++) {
				pulse[k][i] = Grain.lookFlashing(i) ? mix(base.getRGB(), 0xFFFFFFFF, k / (double) PULSE_STEPS)
						: base.getRGB();
			}
			grey[i] = greyOf(Grain.lookShade(i), Grain.lookPattern(i) && look.patterns());
		}
		for (SandColor color : SandColor.values()) {
			BufferedImage img = new BufferedImage(G, G, BufferedImage.TYPE_INT_ARGB);
			for (int r = 0; r < G; r++) {
				for (int col = 0; col < G; col++) {
					img.setRGB(col, r, pulse[0][Grain.look(Grain.of(color, TILE_SHADES[r * G + col], r, col))]);
				}
			}
			tiles[color.code()] = img;
		}
	}

	/** 설정의 그리는 방법에 맞는 색 표 (처음 한 번 만들고 다시 쓴다). */
	static SandColors of(ColorPalettes.Look look) {
		return CACHE.computeIfAbsent(look, SandColors::new);
	}

	/**
	 * 반짝이는 알갱이를 흰색 쪽으로 {@code mix}(0~1) 섞은 표 ({@value #PULSE_STEPS} 단계로 반올림). 반짝이지 않는 알갱이는 제
	 * 색이다. 빈칸(번호 0)은 0(투명). 돌려준 배열은 고치지 않는다.
	 */
	int[] table(double mix) {
		return pulse[(int) Math.round(Motion.clamp01(mix) * PULSE_STEPS)];
	}

	/** 그리기 번호 {@code look} 의 회색 (판이 끝날 때). */
	int grey(int look) {
		return grey[look];
	}

	/** 홀드·미리보기 블록의 한 칸 (8 × 8 알갱이 그림, 반짝이지 않는 색). */
	BufferedImage tile(SandColor color) {
		return tiles[color.code()];
	}

	/** 바탕색 (미리보기 칸의 테두리, 고스트, 조각). */
	Color sand(int colorCode) {
		return look.sand(colorCode);
	}

	/** 알갱이 명암을 살린 회색. 무늬 알갱이는 조금 어둡게(무늬가 회색에서도 보이게). */
	private static int greyOf(int shade, boolean pattern) {
		double[] tone = { -0.12, -0.04, 0.05, 0.12 };
		double t = tone[shade & 3];
		int c = t < 0 ? mix(KO_GREY.getRGB(), 0xFF000000, -t) : mix(KO_GREY.getRGB(), 0xFFFFFFFF, t);
		return pattern ? mix(c, 0xFF000000, 0.18) : c;
	}

	/** 두 ARGB 를 t(0~1) 로 섞는다 (불투명). */
	static int mix(int a, int b, double t) {
		int ar = (a >> 16) & 255;
		int ag = (a >> 8) & 255;
		int ab = a & 255;
		int r = (int) Math.round(ar + (((b >> 16) & 255) - ar) * t);
		int gg = (int) Math.round(ag + (((b >> 8) & 255) - ag) * t);
		int bb = (int) Math.round(ab + ((b & 255) - ab) * t);
		return 0xFF000000 | r << 16 | gg << 8 | bb;
	}

	/** 두 ARGB 를 0~256 의 정수 비율로 섞는다 (그리기 고리 안, 빠르게). */
	static int blend(int a, int b, int w256) {
		int iw = 256 - w256;
		int r = (((a >> 16) & 255) * iw + ((b >> 16) & 255) * w256) >> 8;
		int gg = (((a >> 8) & 255) * iw + ((b >> 8) & 255) * w256) >> 8;
		int bb = ((a & 255) * iw + (b & 255) * w256) >> 8;
		return 0xFF000000 | r << 16 | gg << 8 | bb;
	}

	/** 미리보기 블록의 알갱이 명암: 고정한 난수로 한 번 정한다(판마다, 화면마다 같다). */
	private static int[] tileShades() {
		SplitMix64 r = new SplitMix64(0x5A4D_5A4DL);
		int[] out = new int[G * G];
		for (int i = 0; i < out.length; i++) {
			out[i] = r.nextInt(4);
		}
		return out;
	}
}
