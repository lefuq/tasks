export function validateTask(name: string, description: string) {
  const trimmedName = name.replace(/^\p{White_Space}+|\p{White_Space}+$/gu, "");
  const errors: { name?: string; description?: string } = {};
  if (!/^[A-Za-zА-Яа-яЁё0-9._-]{1,100}$/.test(trimmedName)) {
    errors.name =
      "Имя: 1–100 символов; латинские и русские буквы, цифры, точка, дефис и подчёркивание. Пробелы внутри запрещены.";
  }
  if ([...description].length > 1000)
    errors.description = "Описание должно содержать не более 1000 символов.";
  return { name: trimmedName, errors };
}
