"""모든 폼에 Bootstrap 클래스를 붙이는 공통 믹스인."""

from django import forms


class BootstrapFormMixin:
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        for field in self.fields.values():
            w = field.widget
            if isinstance(w, forms.RadioSelect):
                css = "form-check-input"
            elif isinstance(w, forms.Select):
                css = "form-select"
            else:
                css = "form-control"
            w.attrs["class"] = (w.attrs.get("class", "") + " " + css).strip()

    def full_clean(self):
        super().full_clean()
        for name in self.errors:
            if name in self.fields:
                w = self.fields[name].widget
                w.attrs["class"] = w.attrs.get("class", "") + " is-invalid"
