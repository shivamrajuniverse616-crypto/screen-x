# Contributing to ScreenX

Thank you for your interest in contributing to ScreenX! We welcome and appreciate contributions of all kinds, whether it's reporting bugs, fixing issues, proposing features, or improving documentation.

---

## How Can I Contribute?

### 🐛 Reporting Bugs
If you find a bug, please open an issue on GitHub. Before doing so, check if the bug has already been reported. When filing an issue, please include:
* A clear and descriptive title.
* Steps to reproduce the bug.
* Expected vs. actual behavior.
* Device model, Android version, and ScreenX version.
* Screenshots, screen recordings, or logcats if applicable.

### 💡 Proposing Enhancements
If you have ideas to improve ScreenX:
* Check the existing issues to see if the feature has been proposed.
* Open an issue explaining your proposed feature, why it is useful, and how it might work.

### 🛠️ Submitting Code Changes
If you'd like to fix a bug or implement a feature:
1. **Fork the Repository** to your own account.
2. **Clone your fork** locally.
3. **Create a branch** for your work, using a descriptive name (e.g., `bugfix/issue-123` or `feature/brush-colors`).
4. **Implement your changes**. Make sure to follow the existing code style (Kotlin coding guidelines, Jetpack Compose best practices).
5. **Test your changes** thoroughly on a device or emulator.
6. **Commit and push** your branch to your fork.
7. **Open a Pull Request (PR)** against the `main` branch of ScreenX.

---

## Code Style & Guidelines

* **Kotlin:** Follow standard Kotlin coding conventions.
* **Jetpack Compose:** Keep composables reusable and stateless where possible, and lift state up.
* **Service Lifecycle:** Ensure the foreground service and notification lifecycle are correctly managed. Always release system resources (e.g. MediaProjection, MediaRecorder) when stopping recording or closing the app.
* **License:** By contributing to ScreenX, you agree that your contributions will be licensed under the project's [MIT License](LICENSE).
