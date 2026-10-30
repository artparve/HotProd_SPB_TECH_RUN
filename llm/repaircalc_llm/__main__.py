from .settings import Settings
from .service import server

if __name__ == "__main__":
    with server(Settings.environment()) as application:
        application.serve_forever()
