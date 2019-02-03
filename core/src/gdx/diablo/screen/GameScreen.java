package gdx.diablo.screen;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.GridPoint2;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.net.Socket;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Touchpad;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.UIUtils;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Timer;

import org.apache.commons.io.IOUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;

import gdx.diablo.Diablo;
import gdx.diablo.Keys;
import gdx.diablo.entity3.Player;
import gdx.diablo.graphics.PaletteIndexedBatch;
import gdx.diablo.graphics.PaletteIndexedColorDrawable;
import gdx.diablo.key.MappedKey;
import gdx.diablo.key.MappedKeyStateAdapter;
import gdx.diablo.map.Map;
import gdx.diablo.map.MapLoader;
import gdx.diablo.map.MapRenderer;
import gdx.diablo.panel.CharacterPanel;
import gdx.diablo.panel.ControlPanel;
import gdx.diablo.panel.EscapePanel;
import gdx.diablo.panel.InventoryPanel;
import gdx.diablo.panel.MobilePanel;
import gdx.diablo.panel.StashPanel;
import gdx.diablo.server.PipedSocket;
import gdx.diablo.widget.TextArea;

public class GameScreen extends ScreenAdapter implements LoadingScreen.Loadable {
  private static final String TAG = "GameScreen";
  private static final boolean DEBUG_TOUCHPAD = true;
  private static final boolean DEBUG_MOBILE   = true;

  final AssetDescriptor<Sound> windowopenDescriptor = new AssetDescriptor<>("data\\global\\sfx\\cursor\\windowopen.wav", Sound.class);

  final AssetDescriptor<Texture> touchpadBackgroundDescriptor = new AssetDescriptor<>("textures/touchBackground.png", Texture.class);
  final AssetDescriptor<Texture> touchpadKnobDescriptor = new AssetDescriptor<>("textures/touchKnob.png", Texture.class);

  Touchpad touchpad;
  public EscapePanel escapePanel;
  Actor left;
  Actor right;

  ControlPanel controlPanel;
  MobilePanel mobilePanel;
  public InventoryPanel inventoryPanel;
  public CharacterPanel characterPanel;
  public StashPanel stashPanel;
  MappedKeyStateAdapter mappedKeyStateListener;

  Stage stage;

  final AssetDescriptor<Map> mapDescriptor = new AssetDescriptor<>("Act 1", Map.class, MapLoader.MapParameters.of(0, 0, 0));
  Map map;
  MapRenderer mapRenderer;
  OrthographicCamera camera;
  InputProcessor inputProcessorTest;

  public TextArea input;
  TextArea output;

  //Char character;
  public Player player;
  Timer.Task updateTask;

  Socket socket;
  PrintWriter out;
  BufferedReader in;

  @Override
  public Array<AssetDescriptor> getDependencies() {
    Array<AssetDescriptor> dependencies = new Array<>();
    dependencies.add(windowopenDescriptor);
    dependencies.add(mapDescriptor);
    return dependencies;
  }

  public GameScreen(Player player) {
    this(player, new PipedSocket());
  }

  //public GameScreen(final Char character) {
  public GameScreen(final Player player, Socket socket) {
    this.player = player;
    this.socket = socket;

    input = new TextArea("", new TextArea.TextFieldStyle() {{
      this.font = Diablo.fonts.fontformal12;
      this.fontColor = Diablo.colors.white;
      this.background = new PaletteIndexedColorDrawable(Diablo.colors.modal);
      this.cursor = new TextureRegionDrawable(Diablo.textures.white);
    }});
    input.setSize(Diablo.VIRTUAL_WIDTH * 0.75f, Diablo.fonts.fontformal12.getLineHeight() * 3);
    input.setPosition(Diablo.VIRTUAL_WIDTH_CENTER - input.getWidth() / 2, 100);
    input.setAlignment(Align.topLeft);
    input.setVisible(false);

    output = new TextArea("", new TextArea.TextFieldStyle() {{
      this.font = Diablo.fonts.fontformal12;
      this.fontColor = Diablo.colors.white;
      this.cursor = new TextureRegionDrawable(Diablo.textures.white);
    }});
    output.setDebug(true);
    output.setSize(Diablo.VIRTUAL_WIDTH * 0.75f, Diablo.fonts.fontformal12.getLineHeight() * 8);
    output.setPosition(10, Diablo.VIRTUAL_HEIGHT - 10, Align.topLeft);
    output.setAlignment(Align.topLeft);
    output.setDisabled(true);
    output.setVisible(true);

    escapePanel = new EscapePanel();

    controlPanel = new ControlPanel(this);
    controlPanel.setPosition(
        Diablo.VIRTUAL_WIDTH_CENTER - (controlPanel.getWidth() / 2),
        0);

    if (Gdx.app.getType() == Application.ApplicationType.Android || DEBUG_MOBILE) {
      mobilePanel = new MobilePanel(this);
      mobilePanel.setPosition(
          Diablo.VIRTUAL_WIDTH  - mobilePanel.getWidth(),
          0);//Diablo.VIRTUAL_HEIGHT - mobilePanel.getHeight());
    }

    inventoryPanel = new InventoryPanel(this);
    inventoryPanel.setPosition(
        Diablo.VIRTUAL_WIDTH - inventoryPanel.getWidth(),
        Diablo.VIRTUAL_HEIGHT - inventoryPanel.getHeight());

    characterPanel = new CharacterPanel(this);
    characterPanel.setPosition(
        0,
        Diablo.VIRTUAL_HEIGHT - characterPanel.getHeight());

    stashPanel = new StashPanel(this);
    stashPanel.setPosition(
        0,
        Diablo.VIRTUAL_HEIGHT - stashPanel.getHeight());

    stage = new Stage(Diablo.viewport, Diablo.batch);
    if (mobilePanel != null) stage.addActor(mobilePanel);
    stage.addActor(input);
    stage.addActor(output);
    stage.addActor(controlPanel);
    stage.addActor(escapePanel);
    stage.addActor(inventoryPanel);
    stage.addActor(characterPanel);
    stage.addActor(stashPanel);
    controlPanel.toFront();
    if (mobilePanel != null) mobilePanel.toFront();
    output.toFront();
    input.toFront();
    escapePanel.toFront();

    if (Gdx.app.getType() == Application.ApplicationType.Android || DEBUG_TOUCHPAD) {
      Diablo.assets.load(touchpadBackgroundDescriptor);
      Diablo.assets.load(touchpadKnobDescriptor);
      Diablo.assets.finishLoadingAsset(touchpadBackgroundDescriptor);
      Diablo.assets.finishLoadingAsset(touchpadKnobDescriptor);
      touchpad = new Touchpad(10, new Touchpad.TouchpadStyle() {{
        //background = new TextureRegionDrawable(Diablo.assets.get(touchpadBackgroundDescriptor));
        background = null;
        knob = new TextureRegionDrawable(Diablo.assets.get(touchpadKnobDescriptor));
      }});
      touchpad.setSize(164, 164);
      touchpad.setPosition(0, 0);
      touchpad.addListener(new ChangeListener() {
        @Override
        public void changed(ChangeEvent event, Actor actor) {
          float x = touchpad.getKnobPercentX();
          float y = touchpad.getKnobPercentY();
          if (x == 0 && y == 0 || UIUtils.shift()) {
            player.setMode("TN");
            return;
          //} else if (-0.5f < x && x < 0.5f
          //        && -0.5f < y && y < 0.5f) {
          //  character.setMode("tw");
          } else {
            player.setMode("RN");
          }

          float rad = MathUtils.atan2(y, x);
          player.setAngle(rad);
        }
      });
      stage.addActor(touchpad);
    }

    mappedKeyStateListener = new MappedKeyStateAdapter() {
      @Override
      public void onPressed(MappedKey key, int keycode) {
        if (key == Keys.Esc) {
          if (escapePanel.isVisible()) {
            escapePanel.setVisible(false);
          } else if (inventoryPanel.isVisible()
           || characterPanel.isVisible()) {
            inventoryPanel.setVisible(false);
            characterPanel.setVisible(false);
          } else {
            escapePanel.setVisible(true);
          }
        } else if (key == Keys.Enter) {
          boolean visible = !input.isVisible();
          if (!visible) {
            String text = input.getText();
            if (!text.isEmpty()) {
              Gdx.app.debug(TAG, text);
              out.println(text);
              input.setText("");
            }
          }

          input.setVisible(visible);
          if (visible) {
            stage.setKeyboardFocus(input);
          }
        } else if (key == Keys.Inventory) {
          inventoryPanel.setVisible(!inventoryPanel.isVisible());
        } else if (key == Keys.Character) {
          stashPanel.setVisible(false);
          characterPanel.setVisible(!characterPanel.isVisible());
        } else if (key == Keys.Stash) {
          characterPanel.setVisible(false);
          stashPanel.setVisible(!stashPanel.isVisible());
        } else if (key == Keys.SwapWeapons) {
          player.setAlternate(!player.isAlternate());
        }
      }
    };

    inputProcessorTest = new InputAdapter() {
      private final float ZOOM_AMOUNT = 0.1f;

      @Override
      public boolean scrolled(int amount) {
        switch (amount) {
          case -1:
            if (UIUtils.ctrl()) {
              camera.zoom = Math.max(0.50f, camera.zoom - ZOOM_AMOUNT);
            }

            break;
          case 1:
            if (UIUtils.ctrl()) {
              camera.zoom = Math.min(5.0f, camera.zoom + ZOOM_AMOUNT);
            }

            break;
          default:
        }

        camera.update();
        return true;
      }

      @Override
      public boolean keyDown(int keycode) {
        switch (keycode) {
          case Input.Keys.TAB:
            if (UIUtils.shift()) {
              MapRenderer.RENDER_DEBUG_WALKABLE = MapRenderer.RENDER_DEBUG_WALKABLE == 0 ? 1 : 0;
            } else {
              MapRenderer.RENDER_DEBUG_GRID++;
              if (MapRenderer.RENDER_DEBUG_GRID > MapRenderer.DEBUG_GRID_MODES) {
                MapRenderer.RENDER_DEBUG_GRID = 0;
              }
            }
            return true;

          default:
            return false;
        }
      }
    };
  }

  @Override
  public void render(float delta) {
    try {
      for (String str; in.ready() && (str = in.readLine()) != null;) {
        output.appendText(str);
        output.appendText("\n");
      }
    } catch (IOException e) {
      Gdx.app.error(TAG, e.getMessage());
    }

    PaletteIndexedBatch b = Diablo.batch;
    b.setPalette(Diablo.palettes.act1);

    b.setProjectionMatrix(camera.combined);
    b.begin();
    //map.draw(b, 0, 0, 30, 13, Diablo.VIRTUAL_WIDTH, Diablo.VIRTUAL_HEIGHT, 1.5f);
    mapRenderer.render();

    // pixel offset of subtile in world-space
    //int spx = + (character.x * Tile.SUBTILE_WIDTH50)  - (character.y * Tile.SUBTILE_WIDTH50);
    //int spy = - (character.x * Tile.SUBTILE_HEIGHT50) - (character.y * Tile.SUBTILE_HEIGHT50);
    //character.draw(b, spx, spy);
    //int spx = + (player.getOrigin().x * Tile.SUBTILE_WIDTH50)  - (player.getOrigin().y * Tile.SUBTILE_WIDTH50);
    //int spy = - (player.getOrigin().x * Tile.SUBTILE_HEIGHT50) - (player.getOrigin().y * Tile.SUBTILE_HEIGHT50);
    //player.draw(b, spx, spy);
    player.draw(b);
    b.end();
    b.setProjectionMatrix(Diablo.viewport.getCamera().combined);

    Diablo.shapes.setAutoShapeType(true);
    Diablo.shapes.setProjectionMatrix(camera.combined);
    Diablo.shapes.begin(ShapeRenderer.ShapeType.Line);
    mapRenderer.renderDebug(Diablo.shapes);
    //player.drawDebug(Diablo.shapes, spx, spy);
    player.drawDebug(Diablo.shapes);
    Diablo.shapes.end();

    stage.act();
    stage.draw();
  }

  @Override
  public void show() {
    Diablo.music.stop();
    Diablo.assets.get(windowopenDescriptor).play();

    map = Diablo.assets.get(mapDescriptor);
    camera = new OrthographicCamera(Diablo.VIRTUAL_WIDTH, Diablo.VIRTUAL_HEIGHT);
    mapRenderer = new MapRenderer(Diablo.batch, camera);
    mapRenderer.setMap(map);
    mapRenderer.resize();

    GridPoint2 origin = map.find(Map.ID.TOWN_ENTRY_1);
    mapRenderer.setPosition(origin);
    if (Gdx.app.getType() == Application.ApplicationType.Android) {
      camera.zoom = 0.80f;
      camera.update();
    }

    //character.x = origin.x;
    //character.y = origin.y;
    player.origin().set(origin);
    Gdx.app.debug(TAG, player.toString());

    Keys.Esc.addStateListener(mappedKeyStateListener);
    Keys.Inventory.addStateListener(mappedKeyStateListener);
    Keys.Character.addStateListener(mappedKeyStateListener);
    Keys.Stash.addStateListener(mappedKeyStateListener);
    Keys.SwapWeapons.addStateListener(mappedKeyStateListener);
    Keys.Enter.addStateListener(mappedKeyStateListener);
    Diablo.input.addProcessor(stage);
    Diablo.input.addProcessor(inputProcessorTest);

    updateTask = Timer.schedule(new Timer.Task() {
      @Override
      public void run() {
        if (UIUtils.shift()) return;
        player.move();
        mapRenderer.setPosition(player.origin());
      }
    }, 0, 1 / 25f);

    if (socket != null && socket.isConnected()) {
      Gdx.app.log(TAG, "connecting to " + socket.getRemoteAddress() + "...");
      in = IOUtils.buffer(new InputStreamReader(socket.getInputStream()));
      out = new PrintWriter(socket.getOutputStream(), true);
    }
  }

  @Override
  public void hide() {
    IOUtils.closeQuietly(in);
    IOUtils.closeQuietly(out);
    socket.dispose();
    Gdx.app.log(TAG, "Disposing socket... " + socket.isConnected());

    Keys.Esc.removeStateListener(mappedKeyStateListener);
    Keys.Inventory.removeStateListener(mappedKeyStateListener);
    Keys.Character.removeStateListener(mappedKeyStateListener);
    Keys.Stash.removeStateListener(mappedKeyStateListener);
    Keys.SwapWeapons.removeStateListener(mappedKeyStateListener);
    Keys.Enter.removeStateListener(mappedKeyStateListener);
    Diablo.input.removeProcessor(stage);
    Diablo.input.removeProcessor(inputProcessorTest);

    updateTask.cancel();
  }

  @Override
  public void dispose() {
    stage.dispose();
    escapePanel.dispose();
    controlPanel.dispose();
    inventoryPanel.dispose();
    characterPanel.dispose();
    if (mobilePanel != null) mobilePanel.dispose();
    if (Diablo.assets.isLoaded(touchpadBackgroundDescriptor)) Diablo.assets.load(touchpadBackgroundDescriptor);
    if (Diablo.assets.isLoaded(touchpadKnobDescriptor))       Diablo.assets.load(touchpadKnobDescriptor);
    Diablo.assets.unload(windowopenDescriptor.fileName);
    for (AssetDescriptor asset : getDependencies()) if (Diablo.assets.isLoaded(asset)) Diablo.assets.unload(asset.fileName);
  }

  @Override
  public void pause() {
    escapePanel.setVisible(true);
  }

  @Override
  public void resume() {
    //escapePanel.setVisible(false);
  }
}