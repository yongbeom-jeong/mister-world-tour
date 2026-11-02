from . import display


def site(request):
    """모든 화면에서 쓰는 테마 목록 (메뉴·푸터·홈)."""
    return {"site_themes": display.themes()}
