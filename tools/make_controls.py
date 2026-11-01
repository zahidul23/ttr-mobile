"""Generate configs/ttr-mobile-controls.icp: the TTR Mobile layout as a BannerHub controls profile."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def element(label, bindings, x, y, scale=1, kind='BUTTON'):
    return dict(type=kind, shape='CIRCLE', bindings=(bindings + ['NONE'] * 4)[:4],
                scale=scale, x=x, y=y, toggleSwitch=False, text=label, iconId=0)


# Landscape layout matching the app (TtrControlLayout on a 2400x1080 phone): joystick bottom left, Jump and Throw
# bottom right, Map / Gags / Cam in a row at the bottom centre. x, y are element centres as fractions of the
# screen. BannerHub (Winlator controls) sizes a round button at 6% of the screen width and a stick at 12%, times scale.
# No Tasks button: BannerHub 3.8.1's Binding enum has no End key.
profile = {
    'id': 412023,
    'name': 'Toontown Rewritten Mobile',
    'description': 'TTR Mobile layout: joystick, Jump, Throw, Map, Gags, Camera. Tasks needs the End key, which BannerHub cannot bind.',
    'gameId': '',
    'cursorSpeed': 1,
    'elements': [
        element('Move', ['KEY_UP', 'KEY_RIGHT', 'KEY_DOWN', 'KEY_LEFT'], .089, .80, 1.2, 'STICK'),
        element('Jump', ['KEY_CTRL_L'], .908, .761, 1.9),
        element('Throw', ['KEY_DEL'], .798, .866, 1.6),
        element('Map', ['KEY_ALT_L'], .432, .90, 1.0),
        element('Gags', ['KEY_HOME'], .50, .90, 1.0),
        element('Cam', ['KEY_TAB'], .568, .90, 1.0),
    ],
}

if __name__ == '__main__':
    (ROOT / 'configs/ttr-mobile-controls.icp').write_text(json.dumps(profile, indent=2) + '\n', encoding='utf-8')
    print('Generated configs/ttr-mobile-controls.icp')
