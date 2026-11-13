from . import display


def site(request):
    """모든 화면에서 쓰는 테마·등급 목록 (메뉴·홈 검색)."""
    return {"site_themes": display.themes(), "site_levels": display.levels()}
