package kr.ac.jbnu.se.tetris.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Point;
import java.awt.Transparency;
import java.awt.event.MouseAdapter;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.function.LongSupplier;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * 메뉴 ↔ 판 사이의 화면 바꾸기 (runs/quality/SPEC.md V11). 창의 유리판(glass pane)에 검은 막을 그리므로 화면(패널)은 아무것도
 * 몰라도 된다.
 * <ol>
 * <li>{@link #OUT_MS} 동안 떠나는 화면을 검게 덮는다({@link Motion#exit}, bezier(0.3, 0, 1, 1)). 판을 떠날 때는 떠나는 화면을
 * 바꾸기를 시작한 순간의 그림(snapshot)으로 그린다: 창이 판을 바로 멈춰도(기권·기록) 멈춘 그림이 그대로 어두워진다. 그림은 화면과 같은
 * 가속 그림(VolatileImage)에 뜨고, 그림이 화면을 다 덮는 동안은 유리판을 불투명하게 해 아래 화면을 다시 그리지 않는다. 메뉴에서 판으로
 * 갈 때는 그림을 뜨지 않고 살아 있는 메뉴 위에 막만 칠한다({@link #run(Component, boolean, Runnable)}).</li>
 * <li>다 덮이면 할 일(카드 바꾸기, 판 시작)을 한다.</li>
 * <li>{@link #IN_MS} 동안 새 화면 위의 막을 걷는다({@link Motion#enter}, easeOutQuint).</li>
 * </ol>
 * 어두워지는 동안({@link #blocking()})에는 마우스를 막고, 키 눌림은 창(TetrisApp 의 KeyEventDispatcher)이 버린다: 시작을 두 번
 * 누르거나 사라지는 메뉴의 값을 바꾸지 않게. 밝아지는 동안은 막지 않는다(마우스도 아래 화면으로 간다). 어두워지는 중에 또
 * 바꾸라고 하면 무시하고(먼저 것이 이긴다), 밝아지는 중이면 할 일을 바로 하고 지금 어둡기에서 이어서 밝아진다.
 * <p>
 * 화면 장식이라 판의 시계가 아니라 벽시계({@link #clock}, ms)를 쓴다. 시험은 시계를 바꿔 넣고 {@link #tick()} 을 직접 부른다.
 */
final class AppFade extends JComponent {

	private static final long serialVersionUID = 1L;

	/** 어두워지는 시간 (ms). */
	static final int OUT_MS = 120;
	/** 밝아지는 시간 (ms). */
	static final int IN_MS = 250;

	private enum Phase {
		NONE, OUT, IN
	}

	/** 시계 (ms). */
	transient LongSupplier clock = () -> System.nanoTime() / 1_000_000L;
	private final transient Timer timer;
	private Phase phase = Phase.NONE;
	private long startedAt;
	/** 밝아지기 시작할 때의 어둡기 (보통 1, 밝아지는 중에 다시 바꾸면 그때의 어둡기). */
	private double inFrom = 1;
	private transient Runnable pending;
	private transient Component source;
	/** 떠나는 화면의 그림 (가속 그림, 또는 창에 붙지 않았으면 보통 그림), 그림을 뜨지 않으면 null. */
	private transient Image snapshot;
	/** 보통 그림의 화면 배율 (가속 그림은 스스로 배율을 안다: 1). */
	private double snapshotScale = 1;

	AppFade() {
		setOpaque(false);
		setVisible(false);
		setFocusable(false); // 키보드 초점은 언제나 아래 화면에
		// 보일 때 마우스를 받아(아무것도 하지 않음) 아래 화면에 가지 않게 한다. 밝아지는 동안은 contains() 가 거짓이라 지나간다.
		MouseAdapter swallow = new MouseAdapter() {
		};
		addMouseListener(swallow);
		addMouseMotionListener(swallow);
		addMouseWheelListener(swallow);
		timer = new Timer(15, e -> tick());
	}

	/**
	 * {@code content} 를 지금 모습의 그림으로 어둡게 덮은 뒤 {@code then} 을 하고 다시 밝아진다 (판을 떠날 때:
	 * {@code run(content, true, then)}).
	 */
	boolean run(Component content, Runnable then) {
		return run(content, true, then);
	}

	/**
	 * {@code content} 를 어둡게 덮은 뒤 {@code then} 을 하고 다시 밝아진다. 화면 스레드에서 부른다. {@code content} 의 크기가 0 이면
	 * 기다리지 않고 바로 한다. 창에 붙은 유리판이면 15ms 타이머가 {@link #tick()} 을 부르고, 창에 붙지 않았으면(시험) 부르는 쪽이
	 * 직접 부른다.
	 *
	 * @param freeze 떠나는 화면을 지금 모습의 그림으로 어둡게 할지. 판을 떠날 때는 창이 판을 바로 멈추므로 true. 메뉴에서 판으로 갈
	 *               때는 어두워지는 0.12초 동안 메뉴가 바뀌지 않으므로 false: 그림을 뜨지 않고(그 시간과 메모리 없이) 살아 있는 화면
	 *               위에 막만 칠한다
	 * @return 바꾸기를 시작했으면(또는 바로 했으면) true. 이미 어두워지는 중이라 무시했으면 false
	 */
	boolean run(Component content, boolean freeze, Runnable then) {
		if (phase == Phase.OUT) {
			return false;
		}
		if (phase == Phase.IN) { // 밝아지는 중: 바로 하고, 지금 어둡기에서 이어서 밝아진다
			inFrom = veil();
			startedAt = now();
			then.run();
			repaint();
			return true;
		}
		if (content == null || content.getWidth() <= 0 || content.getHeight() <= 0) {
			then.run();
			return true;
		}
		source = content;
		snapshot = freeze ? capture(content) : null;
		pending = then;
		phase = Phase.OUT;
		startedAt = now();
		setOpaque(snapshot != null); // 그림이 다 덮는 동안은 아래 화면을 다시 그리지 않는다 (불투명한 유리판)
		setVisible(true);
		repaint();
		if (isDisplayable() && !timer.isRunning()) {
			timer.start();
		}
		return true;
	}

	/** 어두워지는 중인가 (그동안 키와 마우스를 막는다). */
	boolean blocking() {
		return phase == Phase.OUT;
	}

	/** 바꾸는 중인가 (어두워지거나 밝아지는 중). */
	boolean active() {
		return phase != Phase.NONE;
	}

	/** 지금 검은 막의 진하기 0~1. */
	double veil() {
		double ms = now() - startedAt;
		switch (phase) {
		case OUT:
			return outVeil(ms);
		case IN:
			return inFrom * inVeil(ms);
		default:
			return 0;
		}
	}

	/** 어두워지기 시작한 지 {@code ms} 뒤의 진하기 (0 → 1). */
	static double outVeil(double ms) {
		return Motion.exit(ms / OUT_MS);
	}

	/** 밝아지기 시작한 지 {@code ms} 뒤의 진하기 (1 → 0). */
	static double inVeil(double ms) {
		return 1 - Motion.enter(ms / IN_MS);
	}

	/** 시간을 한 번 본다: 다 어두워졌으면 할 일을 하고 밝아지기 시작, 다 밝아졌으면 끝. 15ms 타이머가 부른다. */
	void tick() {
		long elapsed = now() - startedAt;
		if (phase == Phase.OUT && elapsed >= OUT_MS) {
			Runnable then = pending;
			pending = null;
			release();
			source = null;
			setOpaque(false); // 밝아지는 동안은 새 화면이 비쳐야 한다
			phase = Phase.IN;
			inFrom = 1;
			startedAt = now();
			if (then != null) {
				then.run();
			}
		} else if (phase == Phase.IN && elapsed >= IN_MS) {
			phase = Phase.NONE;
			timer.stop();
			setVisible(false);
		}
		repaint();
	}

	private long now() {
		return clock.getAsLong();
	}

	/** 떠나는 화면의 그림을 버린다 (가속 그림이면 화면 메모리도 바로 돌려준다). */
	private void release() {
		if (snapshot instanceof VolatileImage vi) {
			vi.flush();
		}
		snapshot = null;
	}

	/** 그림이 화면을 다 덮어 유리판이 불투명한가 (어두워지는 동안, 그림을 떴을 때). */
	boolean frozen() {
		return phase == Phase.OUT && snapshot != null;
	}

	/** 밝아지는 동안과 쉬는 동안에는 마우스가 지나가게 한다 (아래 화면이 받는다). */
	@Override
	public boolean contains(int x, int y) {
		return phase == Phase.OUT && super.contains(x, y);
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		if (phase == Phase.NONE) {
			return;
		}
		Graphics2D g = (Graphics2D) graphics.create();
		if (phase == Phase.OUT && snapshot != null && source != null) {
			if (isOpaque()) { // 그림 밖(메뉴 막대 자리 등)과 잃어버린 그림 자리는 검게
				g.setColor(Color.BLACK);
				g.fillRect(0, 0, getWidth(), getHeight());
			}
			boolean lost = snapshot instanceof VolatileImage vi && vi.contentsLost();
			if (!lost) {
				Point at = SwingUtilities.convertPoint(source, 0, 0, this);
				g.drawImage(snapshot, at.x, at.y, (int) Math.round(snapshot.getWidth(null) / snapshotScale),
						(int) Math.round(snapshot.getHeight(null) / snapshotScale), null);
			}
		}
		float a = (float) Motion.clamp01(veil());
		if (a > 0) {
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, getWidth(), getHeight());
		}
		g.dispose();
	}

	/**
	 * {@code c} 를 지금 모습 그대로 그림으로 뜬다. 창에 붙어 있으면 화면과 같은 가속 그림(VolatileImage, 화면 배율도 스스로 안다)에
	 * 그린다: 보통 그림에 소프트웨어로 그리는 것보다 몇 배 빠르고 자바 힙을 쓰지 않는다. 그리는 사이에 그림을 잃으면 한 번 더 그리고, 또
	 * 잃거나 가속 그림을 못 만들면 보통 그림에 그린다.
	 */
	private Image capture(Component c) {
		GraphicsConfiguration gc = c.getGraphicsConfiguration();
		if (gc != null) {
			try {
				VolatileImage vi = gc.createCompatibleVolatileImage(c.getWidth(), c.getHeight(), Transparency.OPAQUE);
				for (int attempt = 0; attempt < 2 && vi != null; attempt++) {
					if (vi.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE) {
						vi.flush();
						vi = gc.createCompatibleVolatileImage(c.getWidth(), c.getHeight(), Transparency.OPAQUE);
						continue;
					}
					Graphics2D g = vi.createGraphics();
					try {
						c.paint(g);
					} finally {
						g.dispose();
					}
					if (!vi.contentsLost()) {
						snapshotScale = 1;
						return vi;
					}
				}
				if (vi != null) {
					vi.flush();
				}
			} catch (RuntimeException e) {
				// 가속 그림을 못 쓰면 아래의 보통 그림
			}
		}
		return captureSoftware(c);
	}

	/** 보통 그림으로 뜬다 (화면 배율만큼 크게: 125%·150% 화면에서도 흐리지 않게). 창에 붙지 않은 화면(시험)도. */
	private BufferedImage captureSoftware(Component c) {
		double scale = 1;
		GraphicsConfiguration gc = c.getGraphicsConfiguration();
		if (gc != null) {
			scale = Math.max(1, gc.getDefaultTransform().getScaleX());
		}
		int w = (int) Math.ceil(c.getWidth() * scale);
		int h = (int) Math.ceil(c.getHeight() * scale);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.scale(scale, scale);
		c.paint(g);
		g.dispose();
		snapshotScale = scale;
		return img;
	}
}
