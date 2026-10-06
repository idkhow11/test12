# 리팩터링 기록 — 기능 추가 전 기반 정리

2026-09-26 작업. 기능을 붙이기 전에 **동작을 바꾸지 않는 범위에서** 버그와 코드 위생 문제를 정리한 내용이다.
클래스 구성(4개 클래스)은 그대로 두었고, 아키텍처 분해는 다음 단계로 남겼다 ([다음 단계](#다음-단계) 참고).

| 파일 | 줄 수 | 비고 |
|---|---|---|
| `Tetrominoes.java` | 5 → 11 | 문서화만 추가 |
| `Shape.java` | 102 → 145 | 상수 static 화, 난수 버그 수정, 회전 불변성 |
| `Board.java` | 272 → 338 | 줄 삭제 버그, 키 입력 버그, 렌더링, 캡슐화 |
| `Tetris.java` | 33 → 60 | EDT 준수, 시작 시점 분리 |

줄 수가 늘어난 것은 대부분 주석/javadoc과 메서드 분리 때문이다.

**컴파일 경고: 6건 → 0건** (`javac -Xlint:all`)

---

## 1. 버그 수정

### 1-1. `p`, `d` 키가 동작하지 않던 문제

`Board.TAdapter.keyPressed()`

`getKeyCode()` 가 돌려주는 **키코드**를 **문자 리터럴**과 비교하고 있었다. 두 체계는 값이 다르다.

```
'p' = 112 → 실제로는 F1 키의 키코드   (VK_P = 80)
'd' = 100 → 실제로는 넘패드 4 의 키코드 (VK_D = 68)
```

즉 **소문자 p 로는 일시정지가 되지 않고 F1 을 눌러야 됐다.** 대문자 `'P'`/`'D'` 는 우연히
`VK_P`/`VK_D` 와 값이 같아서 겨우 동작하던 상태였다.

```java
// 수정 전
if (keycode == 'p' || keycode == 'P') { pause(); return; }
...
case 'd': oneLineDown(); break;
case 'D': oneLineDown(); break;

// 수정 후
if (keycode == KeyEvent.VK_P) { pause(); return; }
...
case KeyEvent.VK_D: oneLineDown(); break;
```

### 1-2. 줄을 지울 때 맨 윗줄이 복제되던 문제

`Board.removeFullLines()`

윗줄을 한 칸씩 끌어내리는 루프가 `k < BOARD_HEIGHT - 1` 까지만 돌아서,
**맨 윗줄(row 21)이 자기 내용을 그대로 유지**했다. 아래로 복사만 되고 비워지지 않아 블록이 복제된다.

원본 코드로 재현한 결과:

```
row 20 (내려온 줄) : ###.######
row 21 (맨 윗줄)   : ###.######   <- 비어 있어야 정상
```

수정: 끌어내린 뒤 맨 윗줄을 명시적으로 비운다.

```java
for (int k = i; k < BOARD_HEIGHT - 1; ++k) {
    for (int j = 0; j < BOARD_WIDTH; ++j)
        board[(k * BOARD_WIDTH) + j] = shapeAt(j, k + 1);
}
// 추가된 부분
for (int j = 0; j < BOARD_WIDTH; ++j)
    board[((BOARD_HEIGHT - 1) * BOARD_WIDTH) + j] = Tetrominoes.NoShape;
```

보통 맨 윗줄이 차기 전에 게임 오버가 나서 눈에 잘 띄지 않았지만, 명백한 로직 결함이다.

### 1-3. 난수에서 배열 인덱스가 음수가 될 수 있던 문제

`Shape.setRandomShape()`

`Math.abs(Integer.MIN_VALUE)` 는 int 오버플로 때문에 **음수가 그대로 남는다.**
그래서 인덱스가 `-1` 이 되어 `ArrayIndexOutOfBoundsException` 이 날 수 있었다.

```java
// 수정 전 — 2^32 분의 1 확률로 인덱스가 -1
int x = Math.abs(r.nextInt()) % 7 + 1;

// 수정 후 — 범위를 받는 오버로드는 음수를 돌려주지 않는다
setShape(VALUES[RANDOM.nextInt(PLAYABLE_SHAPE_COUNT) + 1]);
```

`7` 도 하드코딩을 없애고 `Tetrominoes.values().length - 1` 로 유도한다.
enum 에 블록을 추가하면 자동으로 따라간다.

### 1-4. 초기화가 끝나기 전에 타이머가 돌 수 있던 문제

`Board` 생성자 / `Tetris.main()`

생성자에서 `statusbar` 와 `board` 를 채우기 **전에** `timer.start()` 를 불렀다.
게다가 `main()` 이 EDT 가 아닌 메인 스레드에서 UI 를 만들었기 때문에,
타이머 콜백(EDT)이 아직 만들어지는 중인 객체를 건드려 `NullPointerException` 이 날 여지가 있었다.

- `Board` 생성자에서 `timer.start()` 를 제거했다. 타이머는 `start()` 에서만 시작한다.
- 필드 초기화를 타이머 생성보다 먼저 오도록 순서를 바꿨다.
- `Tetris.main()` 을 `SwingUtilities.invokeLater()` 로 감쌌다.

```java
public static void main(String[] args) {
    SwingUtilities.invokeLater(() -> {
        Tetris game = new Tetris();
        game.setLocationRelativeTo(null);
        game.setVisible(true);
        game.startGame();
    });
}
```

### 1-5. `paint()` 대신 `paintComponent()`

`Board`

`paint()` 는 컴포넌트 본문 + 테두리 + 자식 컴포넌트 그리기를 모두 맡는 메서드라
재정의 대상이 아니다. Swing 에서 그림을 그릴 때는 `paintComponent()` 를 재정의해야 한다.

```java
// 수정 전
public void paint(Graphics g) { super.paint(g); ... }

// 수정 후
@Override
protected void paintComponent(Graphics g) { super.paintComponent(g); ... }
```

---

## 2. 성능

### 2-1. 좌표 표를 호출마다 새로 만들던 문제

`Shape.setShape()` 안에서 8×4×2 짜리 3차원 배열 `coordsTable` 을
**호출될 때마다** 새로 할당했다. 블록 생성마다, 회전마다(`new Shape()` → `setShape`) 반복된다.
값이 고정이므로 `private static final COORDS_TABLE` 로 올렸다.

`new Random()` 도 `setRandomShape()` 호출마다 만들던 것을 `static final` 필드로 바꿨다.

### 2-2. 색상 배열을 칸마다 새로 만들던 문제

`Board.drawSquare()` 가 `Color` 객체 8개짜리 배열을 **칸 하나 그릴 때마다** 생성했다.
한 프레임에 최대 220칸이므로 프레임마다 1,760개의 `Color` 가 버려졌다.
`private static final COLORS` 로 올렸다.

`drawSquare()` 안에서 `squareWidth()`/`squareHeight()` 를 6번씩 다시 호출하던 것도
지역 변수로 한 번만 받도록 바꿨다.

### 2-3. 하드 드롭이 매 칸마다 다시 그리던 문제

`Board.dropDown()` 이 한 칸 내려갈 때마다 `tryMove()` 를 불렀고,
`tryMove()` 는 성공할 때마다 `repaint()` 를 호출했다. 착지 지점만 계산하도록 바꿨다.

```java
// 수정 후
int newY = curY;
while (newY > 0 && canMove(curPiece, curX, newY - 1)) {
    --newY;
}
curY = newY;
pieceDropped();
```

---

## 3. 설계 정리

### 3-1. `canMove()` 와 `tryMove()` 분리

`tryMove()` 는 이름과 달리 `curPiece`, `curX`, `curY` 를 바꾸고 `repaint()` 까지 하는
부작용 덩어리였다 (명령-조회 분리 위반). "놓을 수 있는가"를 묻는 순수 함수를 떼어냈다.

```java
/** 부작용 없는 순수 검사. */
private boolean canMove(Shape piece, int newX, int newY) { ... }

/** 놓을 수 있으면 실제로 상태를 갱신하고 다시 그린다. */
private boolean tryMove(Shape newPiece, int newX, int newY) {
    if (!canMove(newPiece, newX, newY)) return false;
    curPiece = newPiece; curX = newX; curY = newY;
    repaint();
    return true;
}
```

**고스트 블록, 월킥(wall kick), AI 평가 함수는 전부 `canMove()` 위에 얹으면 된다.**
이번 정리에서 다음 기능 작업에 가장 직접적으로 도움이 되는 부분이다.

### 3-2. 캡슐화

`Board` 의 모든 필드가 package-private 이었다. 전부 `private` 으로 내리고,
바뀌지 않는 것(`timer`, `statusbar`, `board`)은 `final` 로 고정했다.
`shapeAt()`, `squareWidth()`, `squareHeight()` 등 내부용 메서드도 `private` 으로 내렸다.

### 3-3. `implements ActionListener` 제거

`Board` 가 `ActionListener` 를 구현해서 `public void actionPerformed(ActionEvent)` 가
외부에 노출돼 있었다. 타이머 콜백은 내부 구현이므로 람다로 바꾸고 `private onTick()` 으로 감췄다.

```java
timer = new Timer(TICK_INTERVAL_MS, event -> onTick());
```

### 3-4. 상수화

| 기존 | 변경 |
|---|---|
| `final int BoardWidth = 10` (인스턴스 필드) | `public static final int BOARD_WIDTH` |
| `final int BoardHeight = 22` | `public static final int BOARD_HEIGHT` |
| `new Timer(400, ...)` | `TICK_INTERVAL_MS` |
| 반복되는 `4` (블록 칸 수) | `Shape.BLOCK_COUNT` |
| `% 7` | `PLAYABLE_SHAPE_COUNT` |
| `setSize(200, 400)` | `WINDOW_WIDTH`, `WINDOW_HEIGHT` |

자바 상수 명명 규칙(`UPPER_SNAKE_CASE`)에 맞췄다.

### 3-5. 회전이 항상 새 객체를 돌려주도록

`rotateLeft()`/`rotateRight()` 가 `SquareShape` 일 때만 `this` 를 그대로 돌려줬다.
"회전은 원본을 바꾸지 않고 새 `Shape` 를 돌려준다"는 규칙이 한 경우만 깨져 있어서,
복사본을 돌려주도록 통일했다.

### 3-6. 게임 시작 시점 분리

`board.start()` 가 `Tetris` 생성자 안에서, 즉 창이 화면에 올라오기 전에 호출됐다.
`Tetris.startGame()` 으로 빼내서 `setVisible(true)` 뒤에 부르도록 했고,
`requestFocusInWindow()` 를 같이 호출해 키 입력 포커스를 확실히 가져가게 했다.

### 3-7. 컴파일 경고 정리

| 경고 | 처리 |
|---|---|
| `serialVersionUID` 누락 (`Tetris`, `Board`) | 추가 |
| 직렬화 불가 필드 `curPiece` | `transient` 로 표시 (게임 상태 직렬화는 지원 안 함) |
| `this-escape` (`Shape`) | 생성자가 public `setShape()` 대신 private `applyShape()` 를 부르도록 |
| `this-escape` (`Tetris`) | 생성자에서 상속 메서드를 부르므로 클래스를 `final` 로 |
| `this-escape` (`Board`) | 근거를 주석으로 남기고 `@SuppressWarnings("this-escape")` |

### 3-8. 문서화

세 클래스에 javadoc 을 달았다. 특히 **`Tetrominoes` 의 선언 순서를
`Shape.COORDS_TABLE` 과 `Board.COLORS` 가 배열 인덱스로 쓴다**는 암묵적 결합을 명시했다.
enum 중간에 값을 끼워넣으면 두 표가 조용히 어긋난다.

---

## 4. 의도적으로 바꾼 동작 1건

**블록 생성 위치**를 `BOARD_WIDTH / 2 + 1` (= 6) 에서 `BOARD_WIDTH / 2` (= 5) 로 바꿨다.
기존 값은 3칸짜리 블록이 x = 5,6,7 에 생성돼 중앙(4.5)보다 눈에 띄게 오른쪽으로 치우쳤다.
**이번 정리에서 유일하게 플레이 감각이 달라지는 변경이므로, 원래대로 되돌리려면 이 한 줄만 고치면 된다.**

```java
// Board.newPiece()
curX = BOARD_WIDTH / 2;
```

---

## 5. 검증

`javac -Xlint:all` 컴파일 (JDK 26) — **에러 0, 경고 0**.

리플렉션으로 내부 로직을 직접 검사하는 임시 테스트를 작성해 확인했다 (프로젝트에는 포함하지 않음).

```
setRandomShape 100만회 예외 없음 / NoShape 미출현 / 7종 전부 등장
7종 전부: 오른쪽 4회전 = 원본, 좌회전 후 우회전 = 원본
SquareShape.rotateRight() 는 새 객체 / rotateRight() 가 원본을 안 건드림
바닥 줄 삭제 / 윗줄 한 칸 하강 / 맨 윗줄 비워짐 / 지운 줄 수 = 1
2줄 동시 삭제 카운트 = 2 / 안 찬 줄 하강 / 그 위 전부 빈 줄
canMove: 좌·우·바닥 경계 거부, 빈 공간 허용, 찬 칸 거부
5000회 블록 생성 모두 보드 안
```

동일한 줄 삭제 테스트를 **수정 전 코드**에 돌려 1-2 버그가 실제로 재현되는 것도 확인했다.

---

## 다음 단계

> **2026-09-29 기준 현황:** 아래 권장 순서 1번(Gradle 도입)과 2번(테스트, JUnit)은 끝났다. `src/Main.java`와 `.iml`은 지웠고, 저장소는 git으로 관리한다. 규칙 부품은 새 `core` 패키지로 따로 구현했으며, `Board` 분해는 이 문서의 `GameEngine`/`BoardRenderer`/`InputHandler` 대신 [docs/PLAN.md](docs/PLAN.md) 2단계의 `GameModel` 설계를 따른다. 아래 내용은 09-26 당시의 기록이다.

이번에 손대지 않은 것들. 우선순위 순.

### 아직 남은 구조 문제

- **`Board` 가 God Class** — 보드 상태(모델) + 그리기(뷰) + 키 입력(컨트롤러) + 게임 루프가
  한 클래스에 338줄. `GameEngine` / `BoardRenderer` / `InputHandler` 로 분리하는 것이
  기능 추가 전 마지막 정리 작업으로 적절하다.
- **`KeyListener` 대신 `InputMap`/`ActionMap`** — 포커스에 의존하지 않고,
  키 매핑을 데이터로 다룰 수 있어 키 설정 기능을 붙이기 쉬워진다.
- **테스트 코드 없음** — 빌드 도구가 없어 JUnit 을 못 쓰는 상태다.
  Gradle 또는 Maven 도입이 선행되어야 한다.
- **빌드 도구 없음** — IntelliJ `.iml` 모듈에만 의존. 언어 레벨도 JDK 1.8 로 잡혀 있다.
- **`src/Main.java`** — IntelliJ 새 프로젝트 템플릿 잔재로, `Hello and welcome!` 만 출력하고
  게임과 무관하다. 삭제 권장. (이 저장소는 git 이 적용돼 있지 않아 되돌릴 수 없으므로
  이번에는 손대지 않았다.)

### 기능으로 분류해 남겨둔 것

- 점수 체계 (현재 상태바에 지운 줄 수만 표시)
- 레벨 / 낙하 속도 증가 (현재 400ms 고정)
- 게임 오버 후 재시작
- 다음 블록 미리보기, 홀드
- 월킥(wall kick) — 벽 옆에서 회전하면 그냥 실패한다
- 소프트 드롭 — 아래 방향키가 회전에 배정돼 있어 자리가 없다.
  현재 키 배치는 `←`/`→` 이동, `↑` 좌회전, `↓` 우회전, `Space` 하드 드롭, `D` 한 칸 하강, `P` 일시정지.

### 권장 순서

1. 빌드 도구(Gradle) 도입 → JUnit 사용 가능
2. 현재 로직에 대한 테스트 작성 (위 5번 검증 항목을 정식 테스트로)
3. `Board` 분해 — 테스트가 있으니 안전하게 진행 가능
4. 기능 추가
