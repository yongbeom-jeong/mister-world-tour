from django.conf import settings
from django.conf.urls.static import static
from django.contrib import admin
from django.urls import include, path
from . import views

urlpatterns = [
    path("", views.home, name="home"),
    path("credits/", views.credits, name="credits"),
    path("admin/", admin.site.urls),
    path("members/", include("apps.members.urls")),
    path("travel/", include("apps.travel.urls")),
    path("souvenir/", include("apps.souvenir.urls")),
    path("speech/", include("apps.speech.urls")),
    path("sms/", include("apps.sms.urls")),
]

if settings.DEBUG:  # 운영에서는 Nginx 가 /media/ 를 제공
    urlpatterns += static(settings.MEDIA_URL, document_root=settings.MEDIA_ROOT)
